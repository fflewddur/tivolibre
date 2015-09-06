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

import java.io.IOException;

class TivoStreamHeader {
    private char[] fileType;
    private int dummy04;
    private int dummy06;
    private int dummy08;
    private int mpegOffset;
    private int numChunks;
    private final CountingDataInputStream input;

    public TivoStreamHeader(CountingDataInputStream inputStream) {
        fileType = new char[4];
        mpegOffset = 0;
        numChunks = 0;
        this.input = inputStream;
    }

    public int getNumChunks() {
        return numChunks;
    }

    public boolean read() {
        try {
            // First four bytes should be the characters "TiVo"
            for (int i = 0; i < 4; i++) {
                byte b = input.readByte();
                fileType[i] = (char) b;
            }
            // Next two bytes are a mystery
            dummy04 = input.readUnsignedShort();
            // Next two bytes tell us about the file's providence
            dummy06 = input.readUnsignedShort();
            // Next two bytes also remain a mystery
            dummy08 = input.readUnsignedShort();
            // Next four bytes are an unsigned int representing where the read MPEG data begins
            mpegOffset = input.readInt();
            // Next two bytes tell us how many TiVo-specific chunks of data are coming
            numChunks = input.readUnsignedShort();
        } catch (IOException e) {
            TivoDecoder.logger.severe("Error reading header: " + e.getLocalizedMessage());
            return false;
        }

        return true;
    }

    public int getMpegOffset() {
        return mpegOffset;
    }

    public TivoStream.Format getFormat() {
        if ((dummy06 & 0x20) == 0x20) {
            return TivoStream.Format.TRANSPORT_STREAM;
        } else {
            return TivoStream.Format.PROGRAM_STREAM;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("[TiVo Header");
        sb.append(String.format(" fileType=%s (%02X:%02X:%02X:%02X)",
                new String(fileType), (int) fileType[0], (int) fileType[1], (int) fileType[2], (int) fileType[3]));
        sb.append(String.format(" dummy04=%04X", dummy04));
        sb.append(String.format(" dummy06=%04X", dummy06));
        sb.append(String.format(" dummy08=%04X", dummy08));
        sb.append(String.format(" mpegOffset=%d", mpegOffset));
        sb.append(String.format(" numChunks=%d", numChunks));
        sb.append("]");

        return sb.toString();
    }
}
