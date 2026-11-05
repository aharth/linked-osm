package com.ontologycentral.osmwrap.dump;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Random access to the blobs of an {@code .osm.pbf} file. The file is a sequence of
 * {@code [u32 BE length][BlobHeader][Blob]}; a Blob holds one {@code OSMHeader} or one
 * {@code OSMData} block, raw or zlib-compressed. Other compressions (lzma, lz4, zstd) are
 * refused: {@code refresh.sh} writes the dump's copy with {@code pbf_compression=zlib}.
 *
 * <p>Thread-safe: every read is a positional read on the shared channel.
 */
public final class PbfFile implements Closeable {

    /** Spec limits: BlobHeader under 64 KiB, Blob under 32 MiB. */
    private static final int MAX_HEADER = 64 * 1024;
    private static final int MAX_BLOB = 32 * 1024 * 1024;

    /** One blob: where it starts, where the next one starts, its type and its payload. */
    public record Blob(long offset, long next, String type, byte[] data) {}

    private final FileChannel ch;
    private final long size;

    public PbfFile(Path path) throws IOException {
        this.ch = FileChannel.open(path, StandardOpenOption.READ);
        this.size = ch.size();
    }

    public long size() {
        return size;
    }

    /** The blob starting at {@code offset}, inflated. */
    public Blob read(long offset) throws IOException {
        int headerLen = readFully(offset, 4).getInt();
        if (headerLen <= 0 || headerLen > MAX_HEADER) {
            throw new IOException("bad BlobHeader length " + headerLen + " at " + offset);
        }
        ProtoReader h = new ProtoReader(readFully(offset + 4, headerLen).array());
        String type = null;
        long dataSize = -1;
        while (h.hasMore()) {
            switch (h.next()) {
                case 1 -> type = h.string();
                case 3 -> dataSize = h.varint();
                default -> h.skip();
            }
        }
        if (type == null || dataSize < 0 || dataSize > MAX_BLOB) {
            throw new IOException("bad BlobHeader at " + offset);
        }
        long blobStart = offset + 4 + headerLen;
        byte[] blob = readFully(blobStart, (int) dataSize).array();
        return new Blob(offset, blobStart + dataSize, type, decode(blob, offset));
    }

    private static byte[] decode(byte[] blob, long offset) throws IOException {
        ProtoReader b = new ProtoReader(blob);
        byte[] raw = null;
        byte[] zlib = null;
        int rawSize = -1;
        while (b.hasMore()) {
            int field = b.next();
            switch (field) {
                case 1 -> raw = b.bytes();
                case 2 -> rawSize = (int) b.varint();
                case 3 -> zlib = b.bytes();
                case 4, 5, 6, 7 -> throw new IOException("blob at " + offset + " uses compression field "
                        + field + " (lzma/bzip2/lz4/zstd); rewrite with osmium -f pbf,pbf_compression=zlib");
                default -> b.skip();
            }
        }
        if (raw != null) {
            return raw;
        }
        if (zlib == null || rawSize < 0 || rawSize > MAX_BLOB) {
            throw new IOException("blob at " + offset + " has no data");
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(zlib);
            byte[] out = new byte[rawSize];
            int n = 0;
            while (n < rawSize) {
                int k = inf.inflate(out, n, rawSize - n);
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                n += k;
            }
            if (n != rawSize) {
                throw new IOException("blob at " + offset + " inflated to " + n + " of " + rawSize + " bytes");
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException("blob at " + offset + ": " + e.getMessage(), e);
        } finally {
            inf.end();
        }
    }

    private ByteBuffer readFully(long pos, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(len);
        while (bb.hasRemaining()) {
            int k = ch.read(bb, pos + bb.position());
            if (k < 0) {
                throw new EOFException("unexpected end of PBF at " + (pos + bb.position()));
            }
        }
        bb.flip();
        return bb;
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
