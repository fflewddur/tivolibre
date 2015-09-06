/*
 * Copyright 2015 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of TivoLibre. TivoLibre is derived from
 * TivoDecode 0.4.4 by Jeremy Drake. See the LICENSE-TivoDecode
 * file for the licensing terms for TivoDecode.
 *
 * TivoLibre is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TivoLibre is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TivoLibre.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package net.straylightlabs.tivolibre;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;

class TivoStreamChunk {
    private int chunkSize;
    private int dataSize;
    private int id;
    private ChunkType type;
    private byte[] data;
    private final CountingDataInputStream inputStream;

    public final static int CHUNK_HEADER_SIZE = 12; // Size of each chunk's header, in bytes
    private final static String MEDIA_MAK_PREFIX = "tivo:TiVo DVR:";
    private final static String LOOKUP_STRING = "0123456789abcdef";

    public TivoStreamChunk(CountingDataInputStream inputStream) {
        this.inputStream = inputStream;
    }

    public boolean isEncrypted() {
        return type == ChunkType.ENCRYPTED;
    }

    public boolean read() {
        try {
            // First four bytes tell us this chunk's size
            chunkSize = inputStream.readInt();
            // The next four bytes tell us the length of the chunk's payload
            dataSize = inputStream.readInt();
            // Two bytes for the chunk's ID
            id = inputStream.readUnsignedShort();
            // Two bytes for the type of payload
            type = ChunkType.valueOf(inputStream.readUnsignedShort());

            // The rest is the payload
            data = new byte[dataSize];
            for (int totalBytesRead = 0, bytesRead = 0; totalBytesRead < dataSize && bytesRead != -1; totalBytesRead += bytesRead) {
                bytesRead = inputStream.read(data);
            }

            // There might be padding bytes at the end of the chunk
            int paddingBytes = chunkSize - dataSize - CHUNK_HEADER_SIZE;
            inputStream.skipBytes(paddingBytes);
        } catch (IOException | IllegalArgumentException e) {
            TivoDecoder.logger.severe("Error reading chunk: " + e.getLocalizedMessage());
            return false;
        }

        return true;
    }

    public int getDataSize() {
        return dataSize;
    }

    public byte[] getKey(String mak) {
        byte[] makBytes = mak.getBytes();
        byte[] bytes = new byte[makBytes.length + dataSize];
        System.arraycopy(makBytes, 0, bytes, 0, makBytes.length);
        System.arraycopy(data, 0, bytes, makBytes.length, dataSize);
        return DigestUtils.sha1(bytes);
    }

    public byte[] getMetadataKey(String mak) {
        byte[] prefixBytes = MEDIA_MAK_PREFIX.getBytes();
        byte[] makBytes = mak.getBytes();
        byte[] bytes = new byte[prefixBytes.length + makBytes.length];
        System.arraycopy(prefixBytes, 0, bytes, 0, prefixBytes.length);
        System.arraycopy(makBytes, 0, bytes, prefixBytes.length, makBytes.length);
        byte[] md5 = DigestUtils.md5(bytes);

        byte[] metaKey = new byte[32];
        for (int i = 0; i < md5.length; i++) {
            metaKey[i * 2] = (byte) LOOKUP_STRING.charAt((md5[i] >> 4) & 0xf);
            metaKey[i * 2 + 1] = (byte) LOOKUP_STRING.charAt(md5[i] & 0xf);
        }
        return getKey(new String(metaKey));
    }

    public void decryptMetadata(TuringDecoder decoder, int offset) {
        TuringStream stream = decoder.prepareFrame(0, 0);
        decoder.skipBytes(stream, offset);
        decoder.decryptBytes(stream, data);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("([TiVo Chunk");
        sb.append(String.format(" chunkSize=%d", chunkSize));
        sb.append(String.format(" dataSize=%d", dataSize));
        sb.append(String.format(" id=%d", id));
        sb.append(String.format(" type=%s", type));
        sb.append("]");

        return sb.toString();
    }

    public String getDataString() {
        return new String(data);
    }

    enum ChunkType {
        PLAINTEXT,
        ENCRYPTED;

        public static ChunkType valueOf(int val) {
            switch (val) {
                case 0:
                    return PLAINTEXT;
                case 1:
                    return ENCRYPTED;
            }
            throw new IllegalArgumentException(String.format("%d is an unsupported chunk type", val));
        }
    }
}
