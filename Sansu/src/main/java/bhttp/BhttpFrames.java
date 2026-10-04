package bhttp;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared frame helpers. Everything is big-endian, header is 8 bytes:
 * u24 length | u8 type | u8 flags | 24 reserved.
 * (same bytes on both sides — the only thing shared was spec.md)
 */
public final class BhttpFrames {

    public static final int T_REQUEST = 0x01;
    public static final int T_RESPONSE = 0x02;
    public static final int T_DATA = 0x03;

    public static final int FLAG_END = 0x01;

    private static final String[] TABLE = new String[11];
    private static final Map<String, Integer> REV = new HashMap<>();

    static {
        TABLE[0x01] = "content-type";
        TABLE[0x02] = "content-length";
        TABLE[0x03] = "server";
        TABLE[0x04] = "date";
        TABLE[0x05] = "host";
        TABLE[0x06] = "user-agent";
        TABLE[0x07] = "accept";
        TABLE[0x08] = "connection";
        TABLE[0x09] = "content-encoding";
        TABLE[0x0A] = "last-modified";
        for (int i = 1; i <= 10; i++) REV.put(TABLE[i], i);
    }

    private BhttpFrames() {}

    public static String nameOf(int code) {
        if (code >= 1 && code <= 10) return TABLE[code];
        return null;
    }

    public static int codeOf(String name) {
        Integer c = REV.get(name.toLowerCase());
        return c == null ? 0 : c;
    }

    /** frame type -> printable name, for -v dumps */
    public static String typeName(int t) {
        if (t == T_REQUEST) return "REQUEST";
        if (t == T_RESPONSE) return "RESPONSE";
        if (t == T_DATA) return "DATA";
        return String.format("TYPE 0x%02x", t);
    }

    /** build a full frame (header + payload) */
    public static byte[] pack(int type, int flags, byte[] payload) {
        int n = payload.length;
        byte[] out = new byte[8 + n];
        out[0] = (byte) ((n >> 16) & 0xFF);
        out[1] = (byte) ((n >> 8) & 0xFF);
        out[2] = (byte) (n & 0xFF);
        out[3] = (byte) type;
        out[4] = (byte) flags;
        out[5] = 0; out[6] = 0; out[7] = 0; // reserved, v2's room
        System.arraycopy(payload, 0, out, 8, n);
        return out;
    }

    public record Header(String name, String value) {}

    /** writer used for both request and response header blocks */
    public static byte[] encodeHeaders(java.util.List<Header> hs) {
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            b.write(hs.size());
            for (Header h : hs) {
                String lname = h.name().toLowerCase();
                int code = codeOf(lname);
                byte[] vb = h.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (code != 0) {
                    b.write(code);
                    b.write((vb.length >> 8) & 0xFF);
                    b.write(vb.length & 0xFF);
                    b.write(vb);
                } else {
                    byte[] nb = lname.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    b.write(0x00);
                    b.write(nb.length);
                    b.write(nb);
                    b.write((vb.length >> 8) & 0xFF);
                    b.write(vb.length & 0xFF);
                    b.write(vb);
                }
            }
            return b.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e); // ByteArrayOutputStream never really throws
        }
    }

    public static class Malformed extends Exception {
        public Malformed(String m) { super(m); }
    }

    public record Decoded(java.util.List<Header> headers, int nextPos) {}

    public static Decoded decodeHeaders(byte[] buf, int pos, int count) throws Malformed {
        java.util.List<Header> out = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                if (pos >= buf.length) throw new Malformed("header truncated");
                int code = buf[pos++] & 0xFF;
                String name;
                if (code == 0x00) {
                    if (pos >= buf.length) throw new Malformed("literal name len missing");
                    int nl = buf[pos++] & 0xFF;
                    name = new String(buf, pos, nl, java.nio.charset.StandardCharsets.UTF_8);
                    pos += nl;
                } else if (code >= 1 && code <= 10) {
                    name = TABLE[code];
                } else {
                    throw new Malformed(String.format("bad header code 0x%02x", code));
                }
                int vl = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
                pos += 2;
                String val = new String(buf, pos, vl, java.nio.charset.StandardCharsets.UTF_8);
                pos += vl;
                out.add(new Header(name, val));
            }
        } catch (IndexOutOfBoundsException e) {
            throw new Malformed("header overruns frame");
        }
        return new Decoded(out, pos);
    }
}
