package com.ontologycentral.osmwrap.dump;

import java.nio.charset.StandardCharsets;

/**
 * Minimal protocol-buffers wire-format reader over a slice of a byte array: just what
 * the two OSM PBF {@code .proto} files ({@code fileformat}, {@code osmformat}) need.
 * No generated code and no protobuf dependency.
 *
 * <p>Usage: {@code while (r.hasMore()) switch (r.next()) { case 1 -> ...; default -> r.skip(); }}
 */
final class ProtoReader {

    private static final int VARINT = 0;
    private static final int FIXED64 = 1;
    private static final int LENGTH_DELIMITED = 2;
    private static final int FIXED32 = 5;

    private final byte[] buf;
    private final int end;
    private int pos;
    private int tag;

    ProtoReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    ProtoReader(byte[] buf, int off, int len) {
        if (off < 0 || len < 0 || off + len > buf.length) {
            throw new IllegalArgumentException("slice out of range");
        }
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
    }

    boolean hasMore() {
        return pos < end;
    }

    /** Reads the next field key and returns its field number. */
    int next() {
        tag = (int) varint();
        return tag >>> 3;
    }

    /** Wire type of the field {@link #next} just read. */
    int wireType() {
        return tag & 7;
    }

    long varint() {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos >= end) {
                throw new IllegalStateException("truncated varint");
            }
            byte b = buf[pos++];
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalStateException("malformed varint");
    }

    /** A zigzag-encoded {@code sint32}/{@code sint64}. */
    long sint() {
        return zigzag(varint());
    }

    static long zigzag(long v) {
        return (v >>> 1) ^ -(v & 1);
    }

    private int length() {
        long len = varint();
        if (len < 0 || len > end - pos) {
            throw new IllegalStateException("length " + len + " beyond message end");
        }
        return (int) len;
    }

    /** The current length-delimited field as its own reader. */
    ProtoReader message() {
        int len = length();
        ProtoReader r = new ProtoReader(buf, pos, len);
        pos += len;
        return r;
    }

    byte[] bytes() {
        int len = length();
        byte[] out = new byte[len];
        System.arraycopy(buf, pos, out, 0, len);
        pos += len;
        return out;
    }

    String string() {
        int len = length();
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    /**
     * Appends the current repeated scalar field to {@code out}, whether it was written
     * packed (one length-delimited run) or unpacked (one varint per occurrence); proto2
     * readers must accept both.
     */
    void repeated(LongList out, boolean zigzag) {
        if (wireType() == LENGTH_DELIMITED) {
            int len = length();
            int stop = pos + len;
            while (pos < stop) {
                long v = varint();
                out.add(zigzag ? zigzag(v) : v);
            }
            if (pos != stop) {
                throw new IllegalStateException("packed field overran its length");
            }
        } else if (wireType() == VARINT) {
            long v = varint();
            out.add(zigzag ? zigzag(v) : v);
        } else {
            throw new IllegalStateException("wire type " + wireType() + " for a repeated varint");
        }
    }

    /** Skips the value of the field {@link #next} just read. */
    void skip() {
        switch (wireType()) {
            case VARINT -> varint();
            case FIXED64 -> advance(8);
            case LENGTH_DELIMITED -> advance(length());
            case FIXED32 -> advance(4);
            default -> throw new IllegalStateException("unsupported wire type " + wireType());
        }
    }

    private void advance(int n) {
        if (n > end - pos) {
            throw new IllegalStateException("field beyond message end");
        }
        pos += n;
    }
}
