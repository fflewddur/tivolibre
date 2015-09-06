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

import java.util.HashMap;
import java.util.Map;

class TuringDecoder {
    private final byte[] key;
    private final Map<Integer, TuringStream> streams;

    private final static int SHORTENED_KEY_LENGTH = 17;

    public TuringDecoder(byte[] key) {
        this.key = key;
        streams = new HashMap<>();
    }

    public void setKey(byte[] newKey) {
        System.arraycopy(newKey, 0, key, 0, newKey.length);
    }

    public TuringStream prepareFrame(int streamId, int blockId) {
        TuringStream stream = streams.get(streamId);
        if (stream == null) {
            stream = new TuringStream(streamId, blockId);
            prepareFrameHelper(stream, streamId, blockId);
            streams.put(streamId, stream);
        }

//        System.out.println("stream.getBlockId() = " + stream.getBlockId());
        if (stream.getBlockId() != blockId) {
            prepareFrameHelper(stream, streamId, blockId);
        }

        return stream;
    }

    private void prepareFrameHelper(TuringStream stream, int streamId, int blockId) {
        // Update our key for the current stream and block
        key[16] = (byte) streamId;
        key[17] = (byte) ((blockId & 0xFF0000) >> 16);
        key[18] = (byte) ((blockId & 0x00FF00) >> 8);
        key[19] = (byte) (blockId & 0x0000FF);

        byte[] shortenedKey = new byte[SHORTENED_KEY_LENGTH];
        System.arraycopy(key, 0, shortenedKey, 0, SHORTENED_KEY_LENGTH);
        byte[] turkey = DigestUtils.sha1(shortenedKey);
//        System.out.println("prepareFrameHelper(): turkey=" + DigestUtils.sha1Hex(shortenedKey));
        byte[] turiv = DigestUtils.sha1(key);
//        System.out.println("prepareFrameHelper(): turiv=" + DigestUtils.sha1Hex(key));

        stream.reset(streamId, blockId, turkey, turiv);
    }

    public void skipBytes(TuringStream stream, int bytesToSkip) {
        if (stream.getCipherPos() + bytesToSkip < stream.getCipherLen()) {
            stream.setCipherPos(stream.getCipherPos() + bytesToSkip);
        } else {
            do {
                bytesToSkip -= stream.getCipherLen() - stream.getCipherPos();
                stream.generate();
            } while (bytesToSkip >= stream.getCipherLen());

            stream.setCipherPos(bytesToSkip);
        }
    }

    public void decryptBytes(TuringStream stream, byte[] buffer) {
        for (int i = 0; i < buffer.length; i++) {
            if (stream.getCipherPos() >= stream.getCipherLen()) {
                stream.generate();
            }
            byte b = stream.getCipherByte();
//            System.out.format("cipher pos: %d cipher len: %d cipher byte: %02x encoded byte: %02x ",
//                    stream.getCipherPos(), stream.getCipherLen(), b, buffer[i]);
            buffer[i] ^= b;
//            System.out.format("decoded byte: %02x%n", buffer[i]);
        }
    }
}
