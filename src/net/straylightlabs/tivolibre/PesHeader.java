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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * This class models the PES headers that are not encrypted in TiVo MPEG-2 Transport Streams.
 * We need to calculate the length of these headers so that we can begin the decryption process after they end.
 */
public class PesHeader {
    private ByteBuffer buffer;
    private int bitPos;
    private int bitLength;
    private boolean isScrambled;

    private static int START_CODE_PREFIX = 0x000001;
    private static int NOT_A_START_CODE = 0xffffffff;
    private static int BITS_PER_BYTE = 8;
    private static int BITS_PER_INT = 32;

    public PesHeader(ByteBuffer source) {
        buffer = source;
        try {
            parseBytes();
        } catch (BufferUnderflowException e) {
//            TivoDecoder.logger.debug("PES BufferUnderflow at bit position {}", bitPos);
        }
        buffer = null;
    }

    public static PesHeader createFrom(ByteBuffer buffer) {
        return new PesHeader(buffer);
    }

    /**
     * Returns the length of the PES headers for the provided buffer.
     */
    public int size() {
        if (isScrambled) {
            // Scrambled packets need to be decoded, so report a length of 0
            return 0;
        }

        int bytes = bitLength / BITS_PER_BYTE;
        if (bitLength % BITS_PER_BYTE != 0) {
            // If the header ends mid-byte, it will be padded to keep the following data byte-aligned
            bytes++;
        }
        return bytes;
    }

    private void parseBytes() {
        if (!nextStartCode()) {
            return;
        }
        int startCodePrefix = getAndAdvanceBits(24);
        int startCodeValue = getAndAdvanceBits(BITS_PER_BYTE);
        while (startCodePrefix == START_CODE_PREFIX) {
            StartCode startCode = StartCode.valueOf(startCodeValue);
//            TivoDecoder.logger.debug("StartCode: " + startCode);
            switch (startCode) {
                case ANCILLARY_DATA:
                case SEQUENCE_END:
                    break;
                case EXTENSION:
                    if (!parseExtensionHeader()) {
                        rewind(BITS_PER_INT);
                        return;
                    }
                    break;
                case PES_HEADER:
                    parsePesHeader(startCodeValue);
                    break;
                case PICTURE:
                    parsePictureHeader();
                    break;
                case PICTURE_GROUP:
                    parsePictureGroup();
                    break;
                case SEQUENCE_HEADER:
                    parseSequenceHeader();
                    break;
                case SLICE:
                    rewind(BITS_PER_INT);
                    return;
                case USER_DATA:
                    parseUserData();
                    break;
                default:
                    TivoDecoder.logger.warn(String.format("Unknown PES start code: 0x%02x", startCodeValue));
                    rewind(BITS_PER_INT);
                    return;
            }

//            TivoDecoder.logger.debug(String.format("startCodePrefix=0x%06x startCode=%s bitPos=%,d bitLength=%,d bitLimit=%,d",
//                    startCodePrefix, startCode, bitPos, bitLength, buffer.limit() * BITS_PER_BYTE));

            if (nextStartCode()) {
                startCodePrefix = getAndAdvanceBits(24);
                startCodeValue = getAndAdvanceBits(BITS_PER_BYTE);
            } else {
                startCodePrefix = NOT_A_START_CODE;
            }
//            TivoDecoder.logger.debug(String.format("next startCodePrefix=0x%08x next startCodeValue=0x%02x bitPos=%,d",
//                    startCodePrefix, startCodeValue, bitPos));
        }
//        TivoDecoder.logger.debug("End of PES header. Bit length = " + bitLength);
    }

    /**
     * Search through the buffer for the next start code. Start codes are prefixed by at least 23 empty bits, and
     * they should be 0-padded such that the 24-bit start code and 8-bit stream ID fit in a byte-aligned 32-bit int.
     */
    private boolean nextStartCode() {
        if (!byteAlign()) {
            return false;
        }

        int startCodePrefix = NOT_A_START_CODE;
        int startCodeLength = 0;
        try {
            startCodePrefix = nextBits(24);
//            if (TivoDecoder.logger.isTraceEnabled()) {
//                TivoDecoder.logger.trace(String.format("startCodePrefix: 0x%06x bitPos: %,d", startCodePrefix, bitPos));
//            }
            while (startCodePrefix == 0) {
                advanceBits(BITS_PER_BYTE);
                startCodeLength += BITS_PER_BYTE;
                startCodePrefix = nextBits(24);
            }
        } catch (BufferUnderflowException e) {
//            TivoDecoder.logger.debug("Ran out of buffer while searching for next start code");
        }
        if (startCodePrefix == START_CODE_PREFIX) {
            return true;
        } else {
            if (startCodeLength > 0) {
//                TivoDecoder.logger.debug("Rewinding {} bits", startCodeLength);
                rewind(startCodeLength);
            }
            return false;
        }
    }

    private boolean byteAlign() {
        while (bitPos % BITS_PER_BYTE != 0) {
            if (nextBits(1) == 1) {
                TivoDecoder.logger.debug("Found a 1 during byte alignment; there can't be another start code after this");
                return false;
            } else {
                advanceBits(1);
            }
        }
        return true;
    }

    private int nextBits(int bits) {
        if (bits > BITS_PER_INT) {
            throw new IllegalArgumentException("Can't read more than 32 bits into an Integer");
        }
        int value = 0;
        int data;
        int alignedReadPos = bitPos;
        int bitsToRead = bits;

        int alignDelta = bitPos % BITS_PER_BYTE;
        if (alignDelta > 0) {
            alignedReadPos -= alignDelta;
            data = readUnsignedByteAt(alignedReadPos / BITS_PER_BYTE);
            int i, j;

            for (i = 0; i < alignDelta; i++) {
                data &= ~(1 << (7 - i));
                alignedReadPos++;
            }
            for (j = alignDelta; (j < BITS_PER_BYTE) && bitsToRead > 0; j++, bitsToRead--) {
                alignedReadPos++;
            }
            if (bitsToRead == 0) {
                for (; j < BITS_PER_BYTE; j++) {
                    data = data >>> 1;
                }
            }
            value = data;
        }

        while (bitsToRead > 0) {
            data = readUnsignedByteAt(alignedReadPos / BITS_PER_BYTE);
            alignedReadPos += BITS_PER_BYTE;
            bitsToRead -= BITS_PER_BYTE;
            value = (value << 8) | data;
        }
        if (bitsToRead < 0) {
            value = value >>> (0 - bitsToRead);
        }

        return value;
    }

    private void advanceBits(int bits) {
        bitPos += bits;
        bitLength += bits;
    }

    private int getAndAdvanceBits(int bits) {
        int val = nextBits(bits);
        advanceBits(bits);
        return val;
    }

    private void rewind(int bits) {
        bitPos -= bits;
        bitLength -= bits;
    }

    /**
     * Parse the start of a PES packet.
     */
    private void parsePesHeader(int startCodeValue) {
        // Skip over packet length field
        advanceBits(16);
        if (StartCode.hasHeaderExtension(startCodeValue)) {
            parsePesHeaderExtension();
        }
    }

    private void parsePesHeaderExtension() {
        // Skip over most flags
        advanceBits(2);
        isScrambled = getAndAdvanceBits(2) > 0;
        advanceBits(12);

        int dataLength = readNextUnsignedByte();
        skipBytes(dataLength);
//        TivoDecoder.logger.info("dataLength: " + dataLength);
    }

    private void parsePictureHeader() {
        advanceBits(10);
        int frameType = getAndAdvanceBits(3);
        int bitsToAdvance = 16;
        if (frameType == 2 || frameType == 3) {
            bitsToAdvance += 4;
        }
        if (frameType == 3) {
            bitsToAdvance += 4;
        }
        advanceBits(bitsToAdvance);

        // Picture headers may end with stuffing bytes; skip over them, but don't let them extend into the next frame
        try {
            while (getAndAdvanceBits(1) == 1) {
                advanceBits(8);
            }
        } catch (BufferUnderflowException e) {
            bitPos = buffer.limit() * BITS_PER_BYTE;
            bitLength = buffer.limit() * BITS_PER_BYTE;
        }
    }

    private void parsePictureGroup() {
        advanceBits(27);
    }

    private void parseSequenceHeader() {
        advanceBits(62);
        boolean hasIntraQuantiserMatrix = (getAndAdvanceBits(1) == 1);
        if (hasIntraQuantiserMatrix) {
            skipBytes(64);
        }
        boolean hasNonIntraQuantiserMatrix = (getAndAdvanceBits(1) == 1);
        if (hasNonIntraQuantiserMatrix) {
            skipBytes(64);
        }
    }

    private boolean parseExtensionHeader() {
        int extensionType = getAndAdvanceBits(4);
        switch (ExtensionType.valueOf(extensionType)) {
            case SEQUENCE:
                parseSequenceExtension();
                break;
            case SEQUENCE_DISPLAY:
                parseSequenceDisplayExtension();
                break;
            case PICTURE_CODING:
                parsePictureCodingExtension();
                break;
            default:
                TivoDecoder.logger.warn("Unknown PES extension header type: {}", extensionType);
                return false;
        }
        return true;
    }

    private void parseSequenceExtension() {
        advanceBits(44);
    }

    private void parseSequenceDisplayExtension() {
        advanceBits(3);
        boolean hasColorDescription = (getAndAdvanceBits(1) == 1);
        int bitsToSkip = 29;
        if (hasColorDescription) {
            bitsToSkip += 24;
        }
        advanceBits(bitsToSkip);
    }

    private void parsePictureCodingExtension() {
        advanceBits(29);
        boolean isCompositeDisplay = (getAndAdvanceBits(1) == 1);
        if (isCompositeDisplay) {
            advanceBits(20);
        }
    }

    private void parseUserData() {
        while (nextBits(24) != START_CODE_PREFIX) {
            advanceBits(BITS_PER_BYTE);
        }
    }

    private int readNextUnsignedByte() {
        try {
            int val = buffer.get(bitPos / BITS_PER_BYTE) & 0xff;
            bitPos += BITS_PER_BYTE;
            bitLength += BITS_PER_BYTE;
            return val;
        } catch (IndexOutOfBoundsException e) {
            throw new BufferUnderflowException();
        }
    }

    private int readUnsignedByteAt(int bytePosition) {
        try {
            return buffer.get(bytePosition) & 0xff;
        } catch (IndexOutOfBoundsException e) {
            throw new BufferUnderflowException();
        }
    }

    private void skipBytes(int toSkip) {
        int bitsToSkip = toSkip * BITS_PER_BYTE;
        if (bitPos + bitsToSkip > buffer.limit() * BITS_PER_BYTE) {
            throw new BufferUnderflowException();
        }
        bitPos += bitsToSkip;
        bitLength += bitsToSkip;
    }

    enum StartCode {
        ANCILLARY_DATA,
        PICTURE,
        PICTURE_GROUP,
        EXTENSION,
        PES_HEADER,
        SEQUENCE_END,
        SEQUENCE_HEADER,
        SLICE,
        USER_DATA,
        UNKNOWN;

        public static StartCode valueOf(int startCode) {
            if (startCode == 0x00) {
                return PICTURE;
            } else if (startCode == 0xB2) {
                return USER_DATA;
            } else if (startCode == 0xB3) {
                return SEQUENCE_HEADER;
            } else if (startCode == 0xB5) {
                return EXTENSION;
            } else if (startCode == 0xB7) {
                return SEQUENCE_END;
            } else if (startCode == 0xB8) {
                return PICTURE_GROUP;
            } else if (startCode == 0xBD || (startCode >= 0xC0) && startCode <= 0xEF) {
                return PES_HEADER;
            } else if ((startCode >= 0x01) && startCode <= 0xAF) {
                return SLICE;
            } else if (startCode == 0XF9) {
                return ANCILLARY_DATA;
            } else {
                return UNKNOWN;
            }
        }

        public static boolean hasHeaderExtension(int startCodeValue) {
            return (startCodeValue == 0xBD || (startCodeValue >= 0xC0 && startCodeValue <= 0xEF));
        }
    }

    enum ExtensionType {
        SEQUENCE,
        SEQUENCE_DISPLAY,
        PICTURE_CODING,
        UNKNOWN;

        public static ExtensionType valueOf(int val) {
            if (val == 1) {
                return SEQUENCE;
            } else if (val == 2) {
                return SEQUENCE_DISPLAY;
            } else if (val == 8) {
                return PICTURE_CODING;
            } else {
                return UNKNOWN;
            }
        }
    }
}
