package app.unichat.matrix;

import app.unichat.bridge.BridgePorts;
import app.unichat.model.RoomFeatures;
import app.unichat.model.VoiceNote;
import com.fasterxml.jackson.databind.node.ArrayNode;
import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import app.unichat.model.RoomFeatures;
import app.unichat.service.ChatService.Listener;
import app.unichat.service.StickerMode;
import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static app.unichat.matrix.BridgeProtocol.*;
import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;
import static app.unichat.matrix.MatrixLogin.userMessage;

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
        boolean emote = text.startsWith("/me ");
        String body = emote ? text.substring(4) : text;
        content.put("msgtype", emote ? "m.emote" : "m.text");
        content.put("body", body);
        addReply(content, replyToId);
        synchronized (proc) {
            proc.addLocalEcho(chatId, txn, m -> {
                m.setType(emote ? MessageType.EMOTE : MessageType.TEXT);
                m.setBody(body);
                m.setReplyToId(replyToId);
            });
        }
        fireLocal(chatId);
        return sendEvent(chatId, "m.room.message", txn, content);
    }

    CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item, String replyToId) {
        Chat c = core.chat(chatId);
        return sendSticker(chatId, item, c == null ? RoomFeatures.UNKNOWN : c.features(),
                c == null ? StickerMode.STICKER : StickerMode.of(c.network()), replyToId);
    }

    /**
     * {@code mode} GIF/VIDEO (the network's choice in Ajustes → Redes): an animated GIF sticker goes out as an mp4,
     * flagged as a GIF (it loops, like WhatsApp's GIFs) or as a plain video.
     */
    CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item,
                                        RoomFeatures features, StickerMode mode, String replyToId) {
        String mime = item.info() != null && item.info().hasNonNull("mimetype") ? item.info().path("mimetype").asText() : item.mime();
        if (mode == StickerMode.STICKER || !"image/gif".equals(mime)) return sendSticker(chatId, item, features, replyToId);
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
    CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item, RoomFeatures features, String replyToId) {
        Chat c = core.chat(chatId);
        return sendSticker(chatId, item, features, c != null && needsCopy(item.url(), c.network()), replyToId);
    }

    /**
     * Direct media is only readable from the bridge it came from: another bridge asks the server, which answers "Remote media
     * not found" (no federation), and the sticker is lost. So what goes to another network goes as a copy on our server.
     */
    static boolean needsCopy(String url, Network network) {
        if (url == null || !url.startsWith("mxc://")) return false;
        String server = url.substring("mxc://".length()).split("/", 2)[0];
        if (BridgePorts.directMediaBase(server) == null) return false;
        String bot = network.botLocalpart();
        return bot == null || !server.equals(BridgePorts.mediaServerName(bot.replaceAll("bot$", "")));
    }

    CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item, RoomFeatures features, boolean copy, String replyToId) {
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
        String target = StickerConverter.target(info.path("mimetype").asText(null), features);
        if (target == null && !copy) return sendEvent(chatId, "m.sticker", txn, content);
        return media.media(item.url(), 0, 0).thenApplyAsync(src -> {
            try {
                if (target == null) {
                    String mime = info.path("mimetype").asText("application/octet-stream");
                    content.put("url", client.upload(src, mime, "sticker." + mime.replaceAll(".*/", "")));
                    info.remove(java.util.List.of("thumbnail_url", "thumbnail_info")); // on the same bridge as the sticker
                } else {
                    StickerConverter.Result r = StickerConverter.convert(src, target, Settings.get().cacheDir().resolve("stickers"));
                    String ext = r.mime().equals(StickerConverter.WEBP) ? ".webp" : ".png";
                    content.put("url", client.upload(r.file(), r.mime(), "sticker" + ext));
                    info.put("mimetype", r.mime());
                    info.put("size", Files.size(r.file()));
                    Files.deleteIfExists(r.file());
                }
                info.remove("fi.mau.bridged_sticker"); // no longer that network's own sticker
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
    private static void addReply(ObjectNode content, String replyToId) {
        if (replyToId == null || replyToId.startsWith(SyncProcessor.LOCAL_PREFIX)) return;
        content.putObject("m.relates_to").putObject("m.in_reply_to").put("event_id", replyToId);
    }

    private CompletableFuture<Void> sendEvent(String chatId, String type, String txn, ObjectNode content) {
        return CompletableFuture.runAsync(() -> {
            try {
                JsonNode resp = client.put(CLIENT + "/rooms/" + enc(chatId) + "/send/" + enc(type) + "/" + enc(txn), content);
                synchronized (proc) {
                    proc.confirmLocalEcho(chatId, txn, resp.path("event_id").asText(null));
                }
                fireLocal(chatId);
            } catch (Exception e) {
                synchronized (proc) {
                    proc.failLocalEcho(chatId, txn);
                }
                fireLocal(chatId);
                throw new CompletionException(e);
            }
        }, core.io);
    }

    private void fireLocal(String chatId) {
        core.fire(l -> l.messagesChanged(chatId));
        core.fire(Listener::chatsChanged);
    }

    CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId) {
        return sendMedia(chatId, file, replyToId, probeMime(file), 0, 0, false);
    }

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
        if (how == null) return CompletableFuture.failedFuture(new MatrixException(0, null, "Este chat no acepta notas de voz"));
        return sendMedia(chatId, note.file(), replyToId, how.mime(), 0, 0, note.durationMs(), content -> {
            content.with("info").put("duration", note.durationMs());
            if (!how.voice()) return;
            content.putObject("org.matrix.msc3245.voice");
            ObjectNode audio = content.putObject("org.matrix.msc1767.audio");
            audio.put("duration", note.durationMs());
            ArrayNode wave = audio.putArray("waveform");
            note.waveform().forEach(wave::add);
        });
    }

    private CompletableFuture<Void> sendMedia(String chatId, Path file, String replyToId, String mime, int width, int height,
                                              boolean gif) {
        return sendMedia(chatId, file, replyToId, mime, width, height, 0, content -> {
            if (gif) content.with("info").put("fi.mau.gif", true);
        });
    }

    /** @param extra adds what's particular to this kind of media to the event's content */
    private CompletableFuture<Void> sendMedia(String chatId, Path file, String replyToId, String mime, int width, int height,
                                              long durationMs, java.util.function.Consumer<ObjectNode> extra) {
        String txn = core.newTxnId();
        String name = file.getFileName().toString();
        MessageType type = mime.startsWith("image/") ? MessageType.IMAGE
                : mime.startsWith("video/") ? MessageType.VIDEO
                : mime.startsWith("audio/") ? MessageType.AUDIO : MessageType.FILE;
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new MatrixException(0, null, "No se pudo leer el archivo"));
        }
        int[] wh = width > 0 ? new int[]{width, height} : type == MessageType.IMAGE ? imageSize(file) : new int[]{0, 0};
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
                return client.upload(file, mime, name);
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
            return sendEvent(chatId, "m.room.message", txn, content);
        });
    }

    private static String probeMime(Path file) {
        try {
            String m = Files.probeContentType(file);
            if (m != null) return m;
        } catch (IOException ignored) {
            // fall through
        }
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static int[] imageSize(Path file) {
        try {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img != null) return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception ignored) {
            // unsupported format
        }
        return new int[]{0, 0};
    }

    CompletableFuture<Void> editMessage(String chatId, String messageId, String newText) {
        if (messageId.startsWith(SyncProcessor.LOCAL_PREFIX)) return notSentYet();
        Message target;
        synchronized (proc) {
            MatrixRoom r = proc.room(chatId);
            target = r == null ? null : r.byId.get(messageId);
            if (target != null) {
                if (!target.isMedia()) target.setBody(newText);
                target.setEdited(true);
            }
        }
        fireLocal(chatId);
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        String msgtype = target != null && target.type() == MessageType.EMOTE ? "m.emote" : "m.text";
        content.put("msgtype", msgtype);
        content.put("body", "* " + newText);
        content.putObject("m.new_content").put("msgtype", msgtype).put("body", newText);
        content.putObject("m.relates_to").put("rel_type", "m.replace").put("event_id", messageId);
        return core.run(() -> client.put(CLIENT + "/rooms/" + enc(chatId) + "/send/m.room.message/" + enc(core.newTxnId()), content));
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
                JsonNode resp = client.put(CLIENT + "/rooms/" + enc(chatId) + "/send/m.reaction/" + enc(core.newTxnId()), content);
                synchronized (proc) {
                    proc.registerOwnReaction(chatId, resp.path("event_id").asText(null), messageId, emoji);
                }
            }
            core.fire(l -> l.messagesChanged(chatId));
        });
    }

    private static CompletableFuture<Void> notSentYet() {
        return CompletableFuture.failedFuture(new MatrixException(0, null, "El mensaje aún no se ha enviado"));
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
