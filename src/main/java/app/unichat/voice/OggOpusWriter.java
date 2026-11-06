package app.unichat.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Opus packets into an Ogg file (RFC 7845), the format of voice notes on every network. Mono. */
final class OggOpusWriter {
    private static final int HEADER_TYPE_CONTINUATION_NONE = 0, FIRST_PAGE = 2, LAST_PAGE = 4;
    private static final int PACKETS_PER_PAGE = 50;
    private static final int[] CRC = crcTable();

    private final OutputStream out;
    private final int serial = (int) System.nanoTime();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final ByteArrayOutputStream lacing = new ByteArrayOutputStream();
    private int packetsInPage;
    private int sequence;
    private long granule;

    /** @param preSkip samples at 48 kHz the decoder should drop at the start (the encoder's delay) */
    OggOpusWriter(OutputStream out, int inputRate, int preSkip) throws IOException {
        this.out = out;
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) 1)
                .putShort((short) preSkip).putInt(inputRate).putShort((short) 0).put((byte) 0);
        writePacketPage(head.array(), FIRST_PAGE);
        byte[] vendor = "Unichat".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tags = ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        tags.put("OpusTags".getBytes(StandardCharsets.US_ASCII)).putInt(vendor.length).put(vendor).putInt(0);
        writePacketPage(tags.array(), HEADER_TYPE_CONTINUATION_NONE);
    }

    /** @param samples48k how long the packet lasts, in samples at 48 kHz */
    void packet(byte[] data, int length, int samples48k) throws IOException {
        for (int left = length; ; left -= 255) {
            lacing.write(Math.min(left, 255));
            if (left < 255) break;
        }
        body.write(data, 0, length);
        granule += samples48k;
        if (++packetsInPage >= PACKETS_PER_PAGE) flush(HEADER_TYPE_CONTINUATION_NONE);
    }

    void finish() throws IOException {
        flush(LAST_PAGE);
    }

    private void writePacketPage(byte[] packet, int type) throws IOException {
        body.write(packet);
        lacing.write(packet.length);
        flush(type);
    }

    private void flush(int type) throws IOException {
        byte[] segments = lacing.toByteArray();
        byte[] data = body.toByteArray();
        ByteBuffer page = ByteBuffer.allocate(27 + segments.length + data.length).order(ByteOrder.LITTLE_ENDIAN);
        page.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) type).putLong(granule)
                .putInt(serial).putInt(sequence++).putInt(0).put((byte) segments.length).put(segments).put(data);
        byte[] bytes = page.array();
        int crc = crc(bytes);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc);
        out.write(bytes);
        body.reset();
        lacing.reset();
        packetsInPage = 0;
    }

    /** Ogg's CRC: polynomial 0x04C11DB7, no reflection, no final inversion. */
    private static int crc(byte[] bytes) {
        int c = 0;
        for (byte b : bytes) c = (c << 8) ^ CRC[((c >>> 24) ^ (b & 0xFF)) & 0xFF];
        return c;
    }

    private static int[] crcTable() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int k = 0; k < 8; k++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            t[i] = r;
        }
        return t;
    }
}
