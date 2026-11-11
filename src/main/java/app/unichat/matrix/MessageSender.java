package app.unichat.matrix;

import app.unichat.bridge.BridgePorts;
import app.unichat.model.VoiceNote;
import com.fasterxml.jackson.databind.node.ArrayNode;
import app.unichat.model.Chat;
import app.unichat.model.Markup;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.MimeTypes;
import app.unichat.model.Mxc;
import app.unichat.model.Network;
import app.unichat.model.RoomFeatures;
import app.unichat.service.ChatService.Listener;
import app.unichat.service.SendAs;
import app.unichat.service.StickerMode;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.support.ImageHeaders;
import app.unichat.video.SendablePhotos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleConsumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;

/** Everything that goes out in a chat: text, stickers, files, edits, deletions, reactions and typing. */
final class MessageSender {
    private final MatrixCore core;
    private final MatrixClient client;
    private final SyncProcessor proc;
    private final String userId;
    private final MediaDownloader media;

    MessageSender(MatrixCore core, MediaDownloader media) {
        this.core = core;
        this.client = core.client;
        this.proc = core.proc;
        this.userId = core.userId;
        this.media = media;
    }

    CompletableFuture<Void> sendText(String chatId, String text, String replyToId) {
        if (text == null || text.isBlank()) return CompletableFuture.completedFuture(null);
        String txn = core.newTxnId();
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        boolean emote = Message.isEmoteCommand(text);
        String body = Message.withoutEmoteCommand(text);
        content.put("msgtype", emote ? "m.emote" : "m.text");
        content.put("body", body);
        String html = formatted(content, body);
        addReply(content, replyToId);
        synchronized (proc) {
            proc.addLocalEcho(chatId, txn, m -> {
                m.setType(emote ? MessageType.EMOTE : MessageType.TEXT);
                m.setBody(body);
                m.setHtml(html);
                m.setReplyToId(replyToId);
            });
        }
        fireLocal(chatId);
        return sendEvent(chatId, "m.room.message", txn, content);
    }

    CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item, String replyToId) {
        Chat c = core.chat(chatId);
        return sendStickerAs(chatId, item, c == null ? RoomFeatures.UNKNOWN : c.features(),
                c == null ? StickerMode.STICKER : StickerMode.of(c.network()), replyToId);
    }

    /**
     * {@code mode} GIF/VIDEO (the network's choice in Ajustes → Redes): an animated GIF sticker goes out as an mp4,
     * flagged as a GIF (it loops, like WhatsApp's GIFs) or as a plain video.
     */
    CompletableFuture<Void> sendStickerAs(String chatId, MediaLibrary.Item item,
                                          RoomFeatures features, StickerMode mode, String replyToId) {
        String mime = item.info() != null && item.info().hasNonNull("mimetype") ? item.info().path("mimetype").asText() : item.mime();
        if (mode == StickerMode.STICKER || !"image/gif".equals(mime)) return sendStickerFor(chatId, item, features, replyToId);
        return media.media(item.url(), 0, 0).thenApplyAsync(src -> {
            try {
                return StickerConverter.toMp4(src, Settings.get().cacheDir().resolve("stickers"));
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, core.io).thenCompose(r -> sendMedia(chatId, r.file(), replyToId, r.mime(), item.width(), item.height(),
                mode == StickerMode.GIF).whenComplete((v, err) -> {
            try {
                Files.deleteIfExists(r.file());
                Files.deleteIfExists(r.file().getParent());
            } catch (IOException ignored) {
                // left in the cache folder
            }
        }));
    }

    /** Sends {@code item}; when the chat refuses its format (a GIF on WhatsApp) a converted copy is uploaded first. */
    CompletableFuture<Void> sendStickerFor(String chatId, MediaLibrary.Item item, RoomFeatures features, String replyToId) {
        Chat c = core.chat(chatId);
        return sendStickerEvent(chatId, item, features, c != null && needsCopy(item.url()), replyToId);
    }

    /**
     * Whether {@code info} names a sticker of {@code network}'s own packs: a converted copy keeps that name, so Telegram's
     * bridge sends the original from the pack, animated. Without it the bridge passes the file through its ffmpeg, and one
     * without a WebP encoder (Homebrew's) fails. The format still has to be one the chat takes: the bridge checks the
     * declared type before looking for the original.
     */
    static boolean ownSticker(JsonNode info, Network network) {
        JsonNode s = info == null ? null : info.get("fi.mau.bridged_sticker");
        return s != null && network.protocolId() != null && network.protocolId().equals(s.path("network").asText())
                && !s.path("pack_url").asText().isEmpty();
    }

    /**
     * A bridge's direct media (mxc://media-<bridge>.localhost/…) is served by that bridge alone, and our server can't reach
     * it (no federation): a bridge that asks the server for it gets "Remote media not found" and the sticker is lost.
     * Telegram's reads its own, Discord's (the older mautrix-discord) doesn't, and any other network's never can, so it
     * always goes as a copy on our server, whatever the chat: no guessing which bridge reads what. A network's own sticker
     * is still known by its {@code fi.mau.bridged_sticker} mark, not by its address.
     */
    static boolean needsCopy(String url) {
        Mxc mxc = Mxc.parse(url);
        return mxc != null && BridgePorts.directMediaBase(mxc.server()) != null;
    }

    /** {@code copy}: uploads the file to our server first, whatever the chat accepts ({@link #needsCopy}). */
    CompletableFuture<Void> sendStickerEvent(String chatId, MediaLibrary.Item item, RoomFeatures features, boolean copy, String replyToId) {
        String txn = core.newTxnId();
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        addReply(content, replyToId);
        content.put("body", "sticker");
        content.put("url", item.url());
        ObjectNode info = item.info() instanceof ObjectNode o ? o.deepCopy() : MatrixClient.JSON.createObjectNode();
        if (!info.has("mimetype") && item.mime() != null) info.put("mimetype", item.mime());
        if (!info.has("w") && item.width() > 0) info.put("w", item.width());
        if (!info.has("h") && item.height() > 0) info.put("h", item.height());
        content.set("info", info);
        synchronized (proc) {
            proc.addLocalEcho(chatId, txn, m -> {
                m.setType(MessageType.STICKER);
                m.setMediaUrl(item.url());
                m.setMimeType(item.mime());
                m.setSize(item.width(), item.height());
                m.setMediaInfo(info.deepCopy());
                m.setReplyToId(replyToId);
            });
        }
        fireLocal(chatId);
        String mimeType = info.path("mimetype").asText(null);
        Chat chat = core.chat(chatId);
        boolean own = chat != null && ownSticker(info, chat.network());
        boolean telegram = chat != null && !own && StickerConverter.forTelegram(chat.network(), mimeType);
        String target = StickerConverter.target(mimeType, features);
        if (target == null && !copy && !telegram) return sendEvent(chatId, "m.sticker", txn, content);
        return media.media(item.url(), 0, 0).thenApplyAsync(src -> {
            try {
                Path dir = Settings.get().cacheDir().resolve("stickers");
                StickerConverter.Result r = telegram ? StickerConverter.toTelegram(src, mimeType, dir)
                        : target != null ? StickerConverter.convert(src, target, dir) : null;
                if (r == null) {
                    String mime = info.path("mimetype").asText("application/octet-stream");
                    content.put("url", client.upload(src, mime, "sticker." + mime.replaceAll(".*/", "")));
                    info.remove(java.util.List.of("thumbnail_url", "thumbnail_info")); // on the same bridge as the sticker
                } else {
                    content.put("url", client.upload(r.file(), r.mime(), "sticker" + StickerConverter.extension(r.mime())));
                    info.put("mimetype", r.mime());
                    info.put("size", Files.size(r.file()));
                    Files.deleteIfExists(r.file());
                }
                if (!own) info.remove("fi.mau.bridged_sticker"); // no longer that network's own sticker
                return content;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, core.io).handle((c, err) -> {
            if (err == null) return sendEvent(chatId, "m.sticker", txn, c);
            Throwable root = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            System.err.println("Sticker conversion failed: " + root.getClass().getSimpleName());
            synchronized (proc) {
                proc.failLocalEcho(chatId, txn);
            }
            fireLocal(chatId);
            return CompletableFuture.<Void>failedFuture(root);
        }).thenCompose(f -> f);
    }

    /**
     * Adds m.relates_to. No "> quote" in the body: Matrix dropped that fallback (v1.13) and bridges pass the body on as
     * it is, so the network would show the quote, Matrix ids included, under the real reply.
     */
    /**
     * The marks in {@code text} (*bold*, _italic_… {@link Markup}) as HTML, which each bridge writes its network's way;
     * the body keeps them as written. Returns the HTML, or null when there are none (plain text, as always).
     */
    private static String formatted(ObjectNode content, String text) {
        String html = Markup.html(text);
        if (html != null) content.put("format", "org.matrix.custom.html").put("formatted_body", html);
        return html;
    }

    private static void addReply(ObjectNode content, String replyToId) {
        if (replyToId == null || replyToId.startsWith(SyncProcessor.LOCAL_PREFIX)) return;
        content.putObject("m.relates_to").putObject("m.in_reply_to").put("event_id", replyToId);
    }

    private CompletableFuture<Void> sendEvent(String chatId, String type, String txn, ObjectNode content) {
        return sendEventId(chatId, type, txn, content).thenApply(id -> null);
    }

    /** As {@link #sendEvent}, giving the event's id. */
    private CompletableFuture<String> sendEventId(String chatId, String type, String txn, ObjectNode content) {
        return CompletableFuture.supplyAsync(() -> {
            readOnReply(chatId);
            try {
                JsonNode resp = client.sendEvent(chatId, type, txn, content);
                String id = resp.path("event_id").asText(null);
                synchronized (proc) {
                    proc.confirmLocalEcho(chatId, txn, id);
                }
                fireLocal(chatId);
                return id;
            } catch (Exception e) {
                synchronized (proc) {
                    proc.failLocalEcho(chatId, txn);
                }
                fireLocal(chatId);
                throw new CompletionException(e);
            }
        }, core.io);
    }

    /** Per chat, the last message a public receipt went out for when sending, so a batch of files sends one. */
    private final Map<String, String> readOnReply = new ConcurrentHashMap<>();

    /**
     * With {@link Settings.ReadReceipts#ON_REPLY}, others see you read their messages once you write: a public m.read
     * for the newest one of theirs, before the message goes. A failure only costs the receipt.
     */
    private void readOnReply(String chatId) {
        if (Settings.get().readReceipts() != Settings.ReadReceipts.ON_REPLY) return;
        String last;
        synchronized (proc) {
            last = proc.lastIncomingEventId(chatId);
        }
        if (last == null || last.equals(readOnReply.get(chatId))) return;
        try {
            client.post(CLIENT + "/rooms/" + enc(chatId) + "/receipt/m.read/" + enc(last), MatrixClient.JSON.createObjectNode());
            readOnReply.put(chatId, last);
        } catch (Exception e) {
            System.err.println("Read receipt on reply failed: " + e);
        }
    }

    private void fireLocal(String chatId) {
        core.fire(l -> l.messagesChanged(chatId));
        core.fire(Listener::chatsChanged);
    }

    /**
     * {@code as}: DOCUMENT sends m.file whatever it is, so the network sends the original instead of a photo; PHOTO
     * makes a picture one the network shows as a photo first ({@link OutgoingPhotos}).
     */
    CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId, SendAs as, DoubleConsumer progress) {
        String mime = MimeTypes.of(file);
        CompletableFuture<String> sent = as == SendAs.PHOTO && SendablePhotos.convertible(mime)
                ? CompletableFuture.supplyAsync(() -> OutgoingPhotos.prepare(file, mime), core.io).thenCompose(p ->
                        sendMedia(chatId, p.file(), replyToId, new Kind(p.mime(), p.width(), p.height(), 0, false, progress), content -> { }))
                : sendMedia(chatId, file, replyToId, new Kind(mime, 0, 0, 0, as == SendAs.DOCUMENT, progress), content -> { });
        Chat c = core.chat(chatId);
        if (c == null || c.network() == app.unichat.model.Network.MATRIX) return sent.thenApply(id -> null);
        // Bridged: done when the network has it (a batch sends the next file only then), or failed if it refused it.
        return sent.thenCompose(id -> id == null ? CompletableFuture.completedFuture(null) : core.statuses.await(id, NETWORK_PATIENCE));
    }

    /** How long a file may take to reach the network before it's taken as sent anyway (a bridge that doesn't report). */
    private static final java.time.Duration NETWORK_PATIENCE = java.time.Duration.ofMinutes(5);

    /** A GIF from the GIF tab: m.video (mp4) or m.image (gif) flagged {@code fi.mau.gif}, so bridges send a GIF. */
    CompletableFuture<Void> sendGif(String chatId, Path file, String mime, int width, int height, String replyToId) {
        return sendMedia(chatId, file, replyToId, mime, width, height, true);
    }

    /**
     * As a voice note where the chat has them: the flag and the waveform are what make a network show a player instead
     * of a file.
     */
    CompletableFuture<Void> sendVoice(String chatId, VoiceNote note, String replyToId) {
        Chat c = core.chat(chatId);
        RoomFeatures.VoiceSend how = (c == null ? RoomFeatures.UNKNOWN : c.features()).voiceSend();
        if (how == null) return CompletableFuture.failedFuture(new MatrixException(0, null, I18n.t("send.noVoice")));
        return sendMedia(chatId, note.file(), replyToId, new Kind(how.mime(), 0, 0, note.durationMs(), false, p -> { }), content -> {
            content.with("info").put("duration", note.durationMs());
            if (!how.voice()) return;
            content.putObject("org.matrix.msc3245.voice");
            ObjectNode audio = content.putObject("org.matrix.msc1767.audio");
            audio.put("duration", note.durationMs());
            ArrayNode wave = audio.putArray("waveform");
            note.waveform().forEach(wave::add);
        }).thenApply(id -> null);
    }

    private CompletableFuture<Void> sendMedia(String chatId, Path file, String replyToId, String mime, int width, int height,
                                              boolean gif) {
        return sendMedia(chatId, file, replyToId, new Kind(mime, width, height, 0, false, p -> { }), content -> {
            if (gif) content.with("info").put("fi.mau.gif", true);
        }).thenApply(id -> null);
    }

    /** What a file is sent as: its MIME, size and length if known, whether as a plain document, and who hears the upload. */
    private record Kind(String mime, int width, int height, long durationMs, boolean document, DoubleConsumer progress) {
        MessageType type() {
            return document ? MessageType.FILE : MessageType.ofMime(mime);
        }
    }

    /** @param extra adds what's particular to this kind of media to the event's content */
    /** @return the event's id once our server has it */
    private CompletableFuture<String> sendMedia(String chatId, Path file, String replyToId, Kind kind,
                                                java.util.function.Consumer<ObjectNode> extra) {
        String mime = kind.mime();
        long durationMs = kind.durationMs();
        String txn = core.newTxnId();
        String name = file.getFileName().toString();
        MessageType type = kind.type();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new MatrixException(0, null, I18n.t("send.unreadableFile")));
        }
        int[] wh = kind.width() > 0 ? new int[]{kind.width(), kind.height()} : type == MessageType.IMAGE ? imageSize(file) : new int[]{0, 0};
        media.ownFile(file);
        synchronized (proc) {
            proc.addLocalEcho(chatId, txn, m -> {
                m.setType(type);
                m.setMediaUrl(file.toUri().toString());
                m.setFileName(name);
                m.setFileSize(size);
                m.setSize(wh[0], wh[1]);
                m.setReplyToId(replyToId);
                m.setMimeType(mime);
                if (durationMs > 0) m.setMediaInfo(MatrixClient.JSON.createObjectNode().put("duration", durationMs));
            });
        }
        fireLocal(chatId);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.upload(file, mime, name, kind.progress());
            } catch (Exception e) {
                synchronized (proc) {
                    proc.failLocalEcho(chatId, txn);
                }
                fireLocal(chatId);
                throw new CompletionException(e);
            }
        }, core.io).thenCompose(uri -> {
            ObjectNode content = MatrixClient.JSON.createObjectNode();
            content.put("msgtype", switch (type) {
                case IMAGE -> "m.image";
                case VIDEO -> "m.video";
                case AUDIO -> "m.audio";
                default -> "m.file";
            });
            content.put("body", name);
            content.put("filename", name);
            content.put("url", uri);
            ObjectNode info = content.putObject("info");
            info.put("mimetype", mime);
            info.put("size", size);
            if (wh[0] > 0) {
                info.put("w", wh[0]);
                info.put("h", wh[1]);
            }
            extra.accept(content);
            addReply(content, replyToId);
            return sendEventId(chatId, "m.room.message", txn, content);
        });
    }

    /** From the header: decoding a phone's photo only to learn its size took 600 MB and more. */
    private static int[] imageSize(Path file) {
        return ImageHeaders.size(file);
    }

    CompletableFuture<Void> editMessage(String chatId, String messageId, String newText) {
        if (messageId.startsWith(SyncProcessor.LOCAL_PREFIX)) return notSentYet();
        Message target;
        synchronized (proc) {
            MatrixRoom r = proc.room(chatId);
            target = r == null ? null : r.byId.get(messageId);
            if (target != null) {
                if (!target.isMedia()) {
                    target.setBody(newText);
                    target.setHtml(Markup.html(newText));
                }
                target.setEdited(true);
            }
        }
        fireLocal(chatId);
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        String msgtype = target != null && target.type() == MessageType.EMOTE ? "m.emote" : "m.text";
        content.put("msgtype", msgtype);
        content.put("body", "* " + newText);
        formatted(content, "* " + newText);
        formatted(content.putObject("m.new_content").put("msgtype", msgtype).put("body", newText), newText);
        content.putObject("m.relates_to").put("rel_type", "m.replace").put("event_id", messageId);
        return core.run(() -> client.sendEvent(chatId, "m.room.message", core.newTxnId(), content));
    }

    CompletableFuture<Void> deleteMessage(String chatId, String messageId) {
        if (messageId.startsWith(SyncProcessor.LOCAL_PREFIX)) {
            synchronized (proc) {
                proc.removeLocal(chatId, messageId);
            }
            fireLocal(chatId);
            return CompletableFuture.completedFuture(null);
        }
        return core.run(() -> {
            redactEvent(chatId, messageId);
            synchronized (proc) {
                MatrixRoom r = proc.room(chatId);
                if (r != null) proc.redact(r, messageId, new SyncProcessor.Result());
            }
            fireLocal(chatId);
        });
    }

    private void redactEvent(String chatId, String eventId) throws IOException {
        client.put(CLIENT + "/rooms/" + enc(chatId) + "/redact/" + enc(eventId) + "/" + enc(core.newTxnId()), null);
    }

    CompletableFuture<Void> toggleReaction(String chatId, String messageId, String emoji) {
        if (messageId.startsWith(SyncProcessor.LOCAL_PREFIX)) return notSentYet();
        return core.run(() -> {
            String own;
            synchronized (proc) {
                own = proc.ownReactionEvent(chatId, messageId, emoji);
            }
            if (own != null) {
                redactEvent(chatId, own);
                synchronized (proc) {
                    MatrixRoom r = proc.room(chatId);
                    if (r != null) proc.redact(r, own, new SyncProcessor.Result());
                }
            } else {
                ObjectNode content = MatrixClient.JSON.createObjectNode();
                content.putObject("m.relates_to").put("rel_type", "m.annotation").put("event_id", messageId).put("key", emoji);
                JsonNode resp = client.sendEvent(chatId, "m.reaction", core.newTxnId(), content);
                synchronized (proc) {
                    proc.registerOwnReaction(chatId, resp.path("event_id").asText(null), messageId, emoji);
                }
            }
            core.fire(l -> l.messagesChanged(chatId));
        });
    }

    private static CompletableFuture<Void> notSentYet() {
        return CompletableFuture.failedFuture(new MatrixException(0, null, I18n.t("send.notSentYet")));
    }

    void sendTyping(String chatId, boolean typing) {
        if (!Settings.get().sendTypingNotifications()) return;
        ObjectNode body = MatrixClient.JSON.createObjectNode().put("typing", typing);
        if (typing) body.put("timeout", 5000);
        core.io.execute(() -> {
            try {
                client.put(CLIENT + "/rooms/" + enc(chatId) + "/typing/" + enc(userId), body);
            } catch (Exception ignored) {
                // best effort
            }
        });
    }
}
