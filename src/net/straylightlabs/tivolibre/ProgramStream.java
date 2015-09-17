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
import java.io.OutputStream;

public class ProgramStream extends Stream {
    private final CountingDataInputStream inputStream;
    private final OutputStream outputStream;
    private final TuringDecoder turingDecoder;
    private TuringStream activeStream;
    private int headerBufferPosition;

    ProgramStream(CountingDataInputStream inputStream, OutputStream outputStream, TuringDecoder turingDecoder) {
        super();
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.turingDecoder = turingDecoder;
    }

    public int processFrame(int code) throws IOException {
        PacketType packetType = PacketType.valueOf(code);
        byte[] header = new byte[32];
        int length;
        headerBufferPosition = 0;

        if (packetType == PacketType.SPECIAL) {
            return 0;
        } else if (packetType == PacketType.PES_SIMPLE || packetType == PacketType.PES_COMPLEX) {
            int scramble = 0;
            int headerLength = 0;
            if (packetType == PacketType.PES_COMPLEX) {
                inputStream.read(header, headerBufferPosition, 5);
                headerBufferPosition += 5;
                if (((header[2] & 0xff) >> 6) != 0x2) {
                    TivoDecoder.logger.warning(
                            String.format("PES (0x%02X) header mark != 0x2: 0x%x (is this an MPEG2-PS file?)",
                                    code, (header[2] & 0xff) >> 6)
                    );
                }
                scramble = (((header[2] & 0xff) >> 4) & 0x3);
                headerLength = (header[4] & 0xff);
                if (scramble == 3) {
                    if (!processScrambledPacket(code, header, headerLength)) {
                        return -1;
                    }
                }
            } else {
                inputStream.read(header, headerBufferPosition, 2);
                headerBufferPosition += 2;
            }
            length = header[1] & 0xff;
            length |= ((header[0] & 0xff) << 8);
            byte[] packetBuffer = new byte[65536 + 8 + 2];
            System.arraycopy(header, 0, packetBuffer, 0, headerBufferPosition);
            int bytesRead = inputStream.read(packetBuffer, headerBufferPosition, length + 2 - headerBufferPosition) + headerBufferPosition;
            if (scramble == 3) {
                int packetOffset;
                int packetSize;
                if (headerLength > 0) {
                    packetOffset = headerBufferPosition;
                    packetSize = length - headerBufferPosition + 2;
                } else {
                    packetOffset = headerBufferPosition;
                    packetSize = length;
                }
                turingDecoder.decryptBytes(activeStream, packetBuffer, packetOffset, packetSize);
                // Toggle the scrambled bit
                packetBuffer[2] &= ~0x30;
            } else if (code == 0xbc) {
                packetBuffer[2] &= ~0x20;
            }

            outputStream.write(code);
            outputStream.write(packetBuffer, 0, Math.min(length + 2, bytesRead));

            return 1;
        }

        return -1;
    }

    private boolean processScrambledPacket(int code, byte[] header, int headerLength) throws IOException {
        if ((header[3] & 0x1) == 0x1) {
            int keyOffset = 6;
            int extByte = 5;
            boolean goAgain;
            if (headerLength > 27) {
                TivoDecoder.logger.severe("Packet header length is too large: " + headerLength);
                return false;
            }

            inputStream.read(header, headerBufferPosition, headerLength);
            headerBufferPosition += headerLength;

            do {
                goAgain = false;

                if ((header[extByte] & 0x20) == 0x20) {
                    keyOffset += 4;
                }

                if ((header[extByte] & 0x80) == 0x80) {
                    if (!processPrivateData(code, header, keyOffset)) {
                        return false;
                    }
                }

                // STD buffer flag
                if ((header[extByte] & 0x10) == 0x10) {
                    keyOffset += 2;
                }

                // extension flag 2
                if ((header[extByte] & 0x1) == 0x1) {
                    extByte = keyOffset;
                    keyOffset++;
                    goAgain = true;
                }
            } while (goAgain);
        }
        return true;
    }

    public boolean processPrivateData(int code, byte[] header, int keyOffset) {
        turingBlockNumber = 0;
        turingCrypted = 0;
        System.arraycopy(header, keyOffset, turingKey, 0, KEY_LENGTH);
        if (!doHeader()) {
            TivoDecoder.logger.severe("doHeader encountered problems");
            return false;
        }
        activeStream = turingDecoder.prepareFrame(code, turingBlockNumber);
        byte[] cryptedBytes = intToByteArray(turingCrypted);
        turingDecoder.decryptBytes(activeStream, cryptedBytes);
        return true;
    }

    private byte[] intToByteArray(int val) {
        byte[] array = new byte[4];
        array[0] = (byte) (val >> 24);
        array[1] = (byte) (val >> 16);
        array[2] = (byte) (val >> 8);
        array[3] = (byte) val;
        return array;
    }

    private enum PacketType {
        SPECIAL,
        PES_SIMPLE,
        PES_COMPLEX,
        NONE;

        static PacketType valueOf(int code) {
            if (code >= 0 && code <= 0xba) {
                return SPECIAL;
            } else if ((code >= 0xbb && code <= 0xbc) || (code >= 0xbe && code <= 0xbf) ||
                    (code >= 0xf0 && code <= 0xf2) || code == 0xf8 || code >= 0xfa && code <= 0xff) {
                return PES_SIMPLE;
            } else if (code == 0xbd || (code >= 0xc0 && code <= 0xef) || (code >= 0xf3 && code <= 0xf7) ||
                    code == 0xf9) {
                return PES_COMPLEX;
            } else {
                return NONE;
            }
        }
    }
}
