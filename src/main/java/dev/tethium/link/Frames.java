package dev.tethium.link;

import com.github.luben.zstd.Zstd;
import java.io.*;
import java.util.Arrays;

/**
 * Wire framing of the phone link: u32 size, u8 flags (bit 0 = zstd), then either the raw frame or u32 raw size + zstd data.
 * A raw frame is one kind byte followed by a {@link Tbin} body. Plain TCP on the home LAN, no TLS by decision.
 */
public final class Frames {
    private Frames() {}
    public static final int PROTOCOL = 1;
    public static final int HELLO = 1, WELCOME = 2, PAIR = 3, PAIRED = 4, BYE = 5, REQUEST = 6, RESPONSE = 7, WATCH = 8, STATE = 9, CONVERSATION = 10, LIVE = 11, PING = 12, PONG = 13, HTTP_REQUEST = 14, HTTP_RESPONSE = 15, HTTP_CANCEL = 16;
    public static final int MAX_FRAME = 32 << 20;
    private static final int COMPRESS_FROM = 64, LEVEL = 3;
    public static byte[] pack(byte[] raw) {
        if (raw.length >= COMPRESS_FROM) {
            byte[] packed = Zstd.compress(raw, LEVEL);
            if (packed.length + 4 < raw.length) {
                var data = new byte[5 + packed.length]; data[0] = 1;
                data[1] = (byte) (raw.length >>> 24); data[2] = (byte) (raw.length >>> 16); data[3] = (byte) (raw.length >>> 8); data[4] = (byte) raw.length;
                System.arraycopy(packed, 0, data, 5, packed.length); return data;
            }
        }
        var data = new byte[1 + raw.length]; System.arraycopy(raw, 0, data, 1, raw.length); return data;
    }
    public static byte[] unpack(byte[] data) throws IOException {
        if (data.length < 2) throw new IOException("Bad frame");
        if ((data[0] & 1) == 0) return Arrays.copyOfRange(data, 1, data.length);
        if (data.length < 6) throw new IOException("Bad frame");
        int size = ((data[1] & 255) << 24) | ((data[2] & 255) << 16) | ((data[3] & 255) << 8) | (data[4] & 255);
        if (size < 1 || size > MAX_FRAME) throw new IOException("Bad frame size");
        byte[] raw = Zstd.decompress(Arrays.copyOfRange(data, 5, data.length), size);
        if (raw.length != size) throw new IOException("Corrupt frame");
        return raw;
    }
    public static void write(DataOutputStream out, byte[] raw) throws IOException { byte[] data = pack(raw); out.writeInt(data.length); out.write(data); out.flush(); }
    public static byte[] read(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size < 2 || size > MAX_FRAME + 5) throw new IOException("Bad frame length");
        byte[] data = new byte[size]; in.readFully(data); return unpack(data);
    }
}
