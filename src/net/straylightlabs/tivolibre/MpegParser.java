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

import java.nio.ByteBuffer;

class MpegParser {
    private ByteBuffer buffer;
    private int headerLength;
    private long bitPos;
    private boolean isEOF;

    private static final int BITS_PER_BYTE = 8;

    public MpegParser(byte[] buffer) {
        this.buffer = ByteBuffer.wrap(buffer);
        headerLength = 0;
    }

    public boolean isEOF() {
        return isEOF;
    }

    public void advanceBits(long bits) {
        bitPos += bits;
        headerLength += bits;
        if (bitPos >= buffer.capacity() * BITS_PER_BYTE) {
            isEOF = true;
        }
    }

    public int nextBits(long bits) {
        int value = 0;
        int data;
        long localBitPos = bitPos;
        long bitsToRead = bits;

        int alignDelta = (int) (localBitPos % BITS_PER_BYTE);
        if (alignDelta > 0) {
            localBitPos -= alignDelta;
            data = readByte(localBitPos);
            int i, j;

            for (i = 0; i < alignDelta; i++) {
                data &= ~(1 << (7 - i));
                localBitPos++;
            }
            for (j = alignDelta; (j < BITS_PER_BYTE) && bitsToRead > 0; j++, bitsToRead--) {
                localBitPos++;
            }
            if (bitsToRead == 0) {
                for (; j < BITS_PER_BYTE; j++) {
                    data = data >> 1;
                }
            }
            value = data;
        }

        while (bitsToRead > 0) {
            data = readByte(localBitPos);
            localBitPos += BITS_PER_BYTE;
            bitsToRead -= BITS_PER_BYTE;
            value = (value << 8) | data;
        }
        if (bitsToRead < 0) {
            value = value >> (0 - bitsToRead);
        }

        return value;
    }

    public int readByte(long offset) {
        try {
            return buffer.get((int) (offset / BITS_PER_BYTE)) & 0xff; // Treat the value as an unsigned int
        } catch (IndexOutOfBoundsException e) {
            isEOF = true;
            return 0;
        }
    }

    private boolean byteAligned() {
        return (bitPos % BITS_PER_BYTE) == 0;
    }

    public void clear() {
        headerLength = 0;
    }

    public void nextStartCode() {
        boolean isAligned = byteAligned();
        while (!isAligned) {
            advanceBits(1);
            isAligned = byteAligned();
        }

        while (!isEOF) {
            if (0x000001 == nextBits(24)) {
                break;
            } else if (0x000000 == nextBits(24)) {
                advanceBits(8);
            } else {
                break;
            }
        }
    }

    public int extensionHeader() {
        advanceBits(32);
        int type = nextBits(4);
        switch (type) {
            case 1:
                sequenceExtension();
                break;
            case 2:
                sequenceDisplayExtension();
                break;
            case 8:
                pictureCodingExtension();
                break;
        }

        return headerLength;
    }

    public int sequenceExtension() {
        advanceBits(48);

        nextStartCode();
        return headerLength;
    }

    public int sequenceDisplayExtension() {
        advanceBits(7);
        int colorDescription = nextBits(1);
        advanceBits(1);
        if (colorDescription == 1) {
            advanceBits(24);
        }
        advanceBits(32);

        nextStartCode();
        return headerLength;
    }

    public int pictureCodingExtension() {
        advanceBits(33);
        int compositeDisplayFlag = nextBits(1);
        advanceBits(1);
        if (compositeDisplayFlag == 1) {
            advanceBits(20);
        }

        nextStartCode();
        return headerLength;
    }

    public int groupOfPicturesHeader() {
        advanceBits(59);
        nextStartCode();
        return headerLength;
    }

    public int userData() {
        advanceBits(32);
        while (nextBits(24) != 0x000001) {
            advanceBits(8);
        }

        nextStartCode();
        return headerLength;
    }

    public int pictureHeader() {
        advanceBits(42);
        int pictureCodingType = nextBits(3);
        advanceBits(19);

        if (pictureCodingType == 2 || pictureCodingType == 3) {
            advanceBits(4);
        }
        if (pictureCodingType == 3) {
            advanceBits(4);
        }
        while (nextBits(1) == 1) {
            advanceBits(9);
        }

        return headerLength;
    }

    public int sequenceHeader() {
        advanceBits(94);
        int loadIntraQuantiserMatrix = nextBits(1);
        advanceBits(1);
        if (loadIntraQuantiserMatrix == 1) {
            advanceBits(8 * 64);
        }
        int loadNonIntraQuantiserMatrix = nextBits(1);
        advanceBits(1);
        if (loadNonIntraQuantiserMatrix == 1) {
            advanceBits(8 * 64);
        }

        nextStartCode();
        return headerLength;
    }

    public int sequenceEnd() {
        advanceBits(32);
        return headerLength;
    }

    public int ancillaryData() {
        advanceBits(32);
        return headerLength;
    }

    public int pesHeader() {
        advanceBits(24);
        boolean extensionPresent = false;
        int streamId = nextBits(8);
        advanceBits(8);
//        System.out.format("streamId: 0x%02x, headerLength=%d%n", streamId, headerLength);
        if (streamId == 0xBD)
            extensionPresent = true;
        else if (streamId == 0xBE)
            extensionPresent = false;
        else if (streamId == 0xBF)
            extensionPresent = false;
        else if ((streamId >= 0xC0) && (streamId <= 0xDF))
            extensionPresent = true;
        else if ((streamId >= 0xE0) && (streamId <= 0xEF))
            extensionPresent = true;

        advanceBits(16);

        if (extensionPresent) {
//            System.out.println("extensionPresent, headerLength=" + headerLength);
            pesHeaderExtension();
        }

        nextStartCode();
        return headerLength;
    }

    public int pesHeaderExtension() {
        int pes_private_data_flag = 0;
        int pack_header_field_flag = 0;
        int program_packet_sequence_counter_flag = 0;
        int p_std_buffer_flag = 0;
        int pes_extension_field_length = 0;
        int PTS_DTS_flags;
        int ESCR_flag;
        int ES_rate_flag;
        int additional_copy_flag;
        int PES_CRC_flag;
        int pes_extension_flag2 = 0;
        int PES_extension_flag;

        advanceBits(8);
        PTS_DTS_flags = nextBits(2);
        advanceBits(2);
        ESCR_flag = nextBits(1);
        advanceBits(1);
        ES_rate_flag = nextBits(1);
        advanceBits(2);
        additional_copy_flag = nextBits(1);
        advanceBits(1);
        PES_CRC_flag = nextBits(1);
        advanceBits(1);
        PES_extension_flag = nextBits(1);
        advanceBits(9);

        if (PTS_DTS_flags == 2) {
            advanceBits(40);
        } else if (PTS_DTS_flags == 3) {
            advanceBits(80);
        }

        if (ESCR_flag != 0) {
            advanceBits(48);
        }
        if (ES_rate_flag != 0) {
            advanceBits(24);
        }
        if (additional_copy_flag != 0) {
            advanceBits(8);
        }
        if (PES_CRC_flag != 0) {
            advanceBits(16);
        }
        if (PES_extension_flag != 0) {
            pes_private_data_flag = nextBits(1);
            advanceBits(1);
            pack_header_field_flag = nextBits(1);
            advanceBits(1);
            program_packet_sequence_counter_flag = nextBits(1);
            advanceBits(1);
            p_std_buffer_flag = nextBits(1);
            advanceBits(4);
            pes_extension_flag2 = nextBits(1);
            advanceBits(1);
        }
        if (pes_private_data_flag != 0) {
            advanceBits(8 * 16);
        }
        if (pack_header_field_flag != 0) {
            advanceBits(8);
        }
        if (program_packet_sequence_counter_flag != 0) {
            advanceBits(16);
        }
        if (p_std_buffer_flag != 0) {
            advanceBits(16);
        }
        if (pes_extension_flag2 != 0) {
            advanceBits(1);
            pes_extension_field_length = nextBits(7);
            advanceBits(15);
        }
        if (pes_extension_field_length != 0) {
            advanceBits(8 * pes_extension_field_length);
        }

        while (0xFF == nextBits(8)) {
            advanceBits(8);
        }

        return headerLength;
    }

    public enum ControlCode {
        PICTURE_START_CODE(0x100),
        SLICE_START_CODE_MIN(0x101),
        SLICE_START_CODE_MAX(0x1AF),
        USER_DATA_START_CODE(0x1B2),
        SEQUENCE_HEADER_CODE(0x1B3),
        SEQUENCE_ERROR_CODE(0x1B4),
        EXTENSION_START_CODE(0x1B5),
        SEQUENCE_END_CODE(0x1B7),
        GROUP_START_CODE(0x1B8),
        SYSTEM_START_CODE_MIN(0x1B9),
        SYSTEM_START_CODE_MAX(0x1FF),
        ISO_END_CODE(0x1B9),
        PACK_START_CODE(0x1BA),
        SYSTEM_START_CODE(0x1BB),
        VIDEO_ELEMENTARY_STREAM(0x1E0),
        ANCILLARY_DATA_CODE(0x1F9),
        UNKNOWN(0);

        private int value;

        ControlCode(int val) {
            value = val;
        }

        public static ControlCode valueOf(int val) {
            for (ControlCode code : values()) {
                if (code.value == val) {
                    return code;
                }
            }
            return UNKNOWN;
        }
    }
}
