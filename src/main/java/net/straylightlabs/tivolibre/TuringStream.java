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

import net.straylightlabs.quickturing.QuickTuring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

class TuringStream {
    private int streamId;
    private int blockId;
    private int cipherPos;
    private int cipherLen;
    private byte[] cipherData;
    private QuickTuring quickTuring;

    private final static Logger logger = LoggerFactory.getLogger(TransportStreamDecoder.class);
    
    public TuringStream(int streamId, int blockId) {
        this.streamId = streamId;
        this.blockId = blockId;
        cipherData = new byte[QuickTuring.MAX_STREAM_LENGTH + Long.BYTES];
        quickTuring = new QuickTuring();
    }

    public int getBlockId() {
        return blockId;
    }

    public int getCipherPos() {
        return cipherPos;
    }

    public void setCipherPos(int val) {
        cipherPos = val;
    }

    public int getCipherLen() {
        return cipherLen;
    }

    /**
     * Return the byte at the current cipher position, then increment the position.
     */
    public byte getCipherByte() {
        return cipherData[cipherPos++];
    }

    public void generate() {
        cipherLen = quickTuring.turingGen(cipherData);
        cipherPos = 0;
    }

    public void reset(int streamId, int blockId, byte[] turkey, byte[] turiv) {
        this.streamId = streamId;
        this.blockId = blockId;
        cipherPos = 0;
        quickTuring.setTuringKey(turkey, 20);
        quickTuring.setTuringIV(turiv, 20);
        Arrays.fill(cipherData, (byte) 0);
        cipherLen = quickTuring.turingGen(cipherData);
    }

    @Override
    public String toString() {
        return "TuringStream{" +
                "streamId=" + streamId +
                ", blockId=" + blockId +
                ", cipherPos=" + cipherPos +
                ", cipherLen=" + cipherLen +
                '}';
    }

    @SuppressWarnings("unused")
    public void dumpCipherData() {
        StringBuilder sb = new StringBuilder();
        int bytesPerBlock = 1;
        int blocksPerLine = 16;
        for (int i = 0; i < cipherLen; i++) {
            sb.append(String.format("%02X", cipherData[i]));
            if ((i + 1) % (blocksPerLine * bytesPerBlock) == 0) {
                sb.append("\n");
            } else if ((i + 1) % bytesPerBlock == 0)
                sb.append(" ");

        }
        logger.info(sb.toString());
    }
}
