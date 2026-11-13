package dev.tethium.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.tethium.json.Json;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Compact binary form of the JSON data model used on the phone link.
 * Tags: 0 null, 1 false, 2 true, 3 integer (zigzag varint), 4 double (8 bytes), 5 string (varint length + UTF-8),
 * 6 array (varint count + items), 7 map (varint count + key,value pairs), 8 bytes (varint length + raw data). A map key is a varint id; id 0 introduces a new key
 * whose text follows once and receives the next id, so key names cross the wire a single time per direction of a connection.
 * Writer and Reader each keep that dictionary, so frames must be encoded and decoded in order.
 */
public final class Tbin {
    private Tbin() {}
    public static final class Writer {
        private final Map<String,Integer> keys = new HashMap<>();
        public byte[] encode(int kind, JsonNode body) {
            var out = new ByteArrayOutputStream(256); out.write(kind); write(out, body); return out.toByteArray();
        }
        private void write(ByteArrayOutputStream out, JsonNode n) {
            if (n == null || n.isNull() || n.isMissingNode()) out.write(0);
            else if (n.isBoolean()) out.write(n.asBoolean() ? 2 : 1);
            else if (n.isIntegralNumber()) { out.write(3); varint(out, zigzag(n.asLong())); }
            else if (n.isNumber()) { out.write(4); long bits = Double.doubleToLongBits(n.asDouble()); for (int i = 7; i >= 0; i--) out.write((int) (bits >>> (8 * i))); }
            else if (n.isArray()) { out.write(6); varint(out, n.size()); for (var e : n) write(out, e); }
            else if (n.isBinary()) { out.write(8); try { bytes(out, n.binaryValue()); } catch (Exception e) { throw new IllegalArgumentException(e); } }
            else if (n.isObject()) { out.write(7); varint(out, n.size()); for (var e : n.properties()) { key(out, e.getKey()); write(out, e.getValue()); } }
            else { out.write(5); text(out, n.asText()); }
        }
        private void key(ByteArrayOutputStream out, String name) {
            Integer id = keys.get(name);
            if (id == null) { out.write(0); keys.put(name, keys.size() + 1); text(out, name); } else varint(out, id);
        }
    }
    public static final class Reader {
        private final List<String> keys = new ArrayList<>();
        private byte[] b; private int p;
        public JsonNode decode(byte[] data, int offset) { b = data; p = offset; var value = read(); b = null; return value; }
        private JsonNode read() {
            int tag = next();
            return switch (tag) {
                case 0 -> NullNode.getInstance();
                case 1 -> BooleanNode.FALSE;
                case 2 -> BooleanNode.TRUE;
                case 3 -> { long v = unzigzag(varint()); yield v == (int) v ? IntNode.valueOf((int) v) : LongNode.valueOf(v); }
                case 4 -> { long bits = 0; for (int i = 0; i < 8; i++) bits = (bits << 8) | next(); yield DoubleNode.valueOf(Double.longBitsToDouble(bits)); }
                case 5 -> TextNode.valueOf(text());
                case 6 -> { int n = count(); var a = Json.MAPPER.createArrayNode(); for (int i = 0; i < n; i++) a.add(read()); yield a; }
                case 7 -> { int n = count(); var o = Json.MAPPER.createObjectNode(); for (int i = 0; i < n; i++) { long id = varint(); String k; if (id == 0) { k = text(); keys.add(k); } else if (id <= keys.size()) k = keys.get((int) id - 1); else throw new IllegalArgumentException("Unknown key id"); o.set(k, read()); } yield o; }
                case 8 -> BinaryNode.valueOf(bytes());
                default -> throw new IllegalArgumentException("Bad tag " + tag);
            };
        }
        private int count() { long n = varint(); if (n < 0 || n > b.length) throw new IllegalArgumentException("Bad count"); return (int) n; }
        private String text() { int n = count(); if (p + n > b.length) throw new IllegalArgumentException("Truncated"); var s = new String(b, p, n, StandardCharsets.UTF_8); p += n; return s; }
        private byte[] bytes() { int n = count(); if (p + n > b.length) throw new IllegalArgumentException("Truncated"); var value = Arrays.copyOfRange(b, p, p + n); p += n; return value; }
        private int next() { if (p >= b.length) throw new IllegalArgumentException("Truncated"); return b[p++] & 255; }
        private long varint() { long v = 0; int shift = 0; while (true) { int x = next(); v |= (long) (x & 127) << shift; if ((x & 128) == 0) return v; shift += 7; if (shift > 63) throw new IllegalArgumentException("Bad varint"); } }
    }
    static void varint(ByteArrayOutputStream out, long v) { while ((v & ~127L) != 0) { out.write((int) ((v & 127) | 128)); v >>>= 7; } out.write((int) v); }
    static void text(ByteArrayOutputStream out, String s) { byte[] bytes = s.getBytes(StandardCharsets.UTF_8); varint(out, bytes.length); out.write(bytes, 0, bytes.length); }
    static void bytes(ByteArrayOutputStream out, byte[] bytes) { varint(out, bytes.length); out.write(bytes, 0, bytes.length); }
    static long zigzag(long v) { return (v << 1) ^ (v >> 63); }
    static long unzigzag(long v) { return (v >>> 1) ^ -(v & 1); }
}
