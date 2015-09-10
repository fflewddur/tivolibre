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
import java.nio.ByteBuffer;

class TransportStreamPacket {
    private long packetId;
    private boolean isPmt;
    private boolean isTivo;
    private Header header;
    private AdaptationField adaptationField;
    private ByteBuffer buffer;
    //    private ByteBuffer data;
    private int dataOffset;
    private int pesHeaderOffset;

    public static TransportStreamPacket createFrom(ByteBuffer source, long packetId) throws IOException {
        TransportStreamPacket packet = new TransportStreamPacket();
        packet.setPacketId(packetId);
        packet.readFrom(source);
        return packet;
    }

    public boolean readFrom(ByteBuffer source) throws IOException {
        // Make a local copy of the buffer
        buffer = ByteBuffer.allocate(source.capacity());
        System.arraycopy(source.array(), 0, buffer.array(), 0, source.capacity());

        header = readHeader(buffer);
        dataOffset = 0;

        return true;
    }

    private Header readHeader(ByteBuffer source) throws IOException {
        int headerLength = Integer.BYTES;
        int headerBits = source.getInt();
        int adaptationFieldLength = 0;
        int adaptationFieldBits = 0;

        header = new Header(headerBits);
        if (!checkHeader(header)) {
            throw new IOException("TransportStream appears to be corrupt, cannot find sync bytes");
        }

        if (header.hasAdaptationField) {
            adaptationFieldLength = source.get() & 0xff;
            if (adaptationFieldLength > 0) {
                adaptationFieldBits = source.get() & 0xff;
                adaptationField = new AdaptationField(adaptationFieldBits);
                if (adaptationField.isPrivate()) {
                    TivoDecoder.logger.severe("Found private adaptation field data; we don't know how to handle this");
                    throw new IOException("Stream contains private adaptation field data");
                }
                headerLength += (adaptationFieldLength + 1);
            } else {
                headerLength++;
            }
        }

        header.setLength(headerLength);

        return header;
    }

    private boolean checkHeader(Header header) {
        if (!header.isValid()) {
            TivoDecoder.logger.severe("Invalid TS packet header");
            return false;
        } else if (header.hasTransportError()) {
            TivoDecoder.logger.severe("Transport error flag set");
            return false;
        }
        return true;
    }

    public byte[] getBytes() {

        if (isScrambled()) {
            throw new IllegalStateException("Cannot get bytes from scrambled packet");
        } else if (!buffer.hasArray()) {
            throw new IllegalStateException("Cannot get bytes from empty packet");
        }

        byte[] bytes = new byte[TransportStream.TS_FRAME_SIZE];
        System.arraycopy(buffer.array(), 0, bytes, 0, TransportStream.TS_FRAME_SIZE);
        return bytes;
    }

    public byte[] getScrambledBytes(byte[] decrypted) {
        byte[] bytes = new byte[TransportStream.TS_FRAME_SIZE];
        if (buffer.hasArray()) {
            System.arraycopy(buffer.array(), 0, bytes, 0, TransportStream.TS_FRAME_SIZE - decrypted.length);
            System.arraycopy(decrypted, 0, bytes, header.getLength() + getPesHeaderOffset(), decrypted.length);
        } else {
            throw new IllegalStateException("Cannot get bytes from empty packet");
        }
        return bytes;
    }

    public boolean isScrambled() {
        return header.isScrambled();
    }

    public void clearScrambled() {
        byte[] bytes = buffer.array();
        bytes[3] &= ~(0xC0);
    }

    public int getPayloadOffset() {
        return header.getLength();
    }

    public void setPesHeaderOffset(int val) {
        pesHeaderOffset = val;
    }

    public int getPesHeaderOffset() {
        return pesHeaderOffset;
    }

    public void setPacketId(long id) {
        packetId = id;
    }

    public long getPacketId() {
        return packetId;
    }

    public PacketType getPacketType() {
        return header.getType();
    }

    public int getPID() {
        return header.getPID();
    }

    public boolean isPayloadStart() {
        return header.isPayloadStart();
    }

//    public int getDataOffset() {
//        return dataOffset;
//    }

    public byte[] getData() {
        return getDataAt(0);
    }

    public byte[] getDataAt(int offset) {
        byte[] data = new byte[TransportStream.TS_FRAME_SIZE - header.getLength() - offset];
        System.arraycopy(buffer.array(), header.getLength() + offset, data, 0, data.length);
        return data;
    }

    public byte[] readBytesFromData(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++, dataOffset += Byte.BYTES) {
            bytes[i] = buffer.get(header.getLength() + dataOffset);
        }
        return bytes;
    }

    public int readIntFromData() {
        int val = buffer.getInt(header.getLength() + dataOffset);
        dataOffset += Integer.BYTES;
        return val;
    }

    public int readUnsignedByteFromData() {
        int val = buffer.get(header.getLength() + dataOffset) & 0xff; // Treat as unsigned byte
        dataOffset += Byte.BYTES;
        return val;
    }

    public int readUnsignedShortFromData() {
        int val = buffer.getShort(header.getLength() + dataOffset) & 0xffff; // Treat as unsigned short
        dataOffset += Short.BYTES;
        return val;
    }

    public void advanceDataOffset(int bytes) {
        dataOffset += bytes;
    }

    public void setIsPmt(boolean val) {
        isPmt = val;
    }

//    public boolean isPmt() {
//        return isPmt;
//    }

    public void setIsTivo(boolean val) {
        isTivo = val;
    }

    public boolean isTivo() {
        return isTivo;
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = buffer.array();
        sb.append("    ");
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format("%02x", bytes[i]));
            if ((i + 1) % 40 == 0) {
                sb.append("\n    ");
            } else if ((i + 1) % 4 == 0) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        String s = String.format("====== Packet: %d ======%nHeader = %s", packetId, header);
        if (header.hasAdaptationField) {
            s += String.format("%nAdaptation field = %s", adaptationField);
        }
        s += String.format("%nBody: isPmt=" + isPmt);
        return s;
    }

    public enum PacketType {
        PROGRAM_ASSOCIATION_TABLE(0x0000, 0x0000),
        CONDITIONAL_ACCESS_TABLE(0x0001, 0x0001),
        RESERVED(0x0002, 0x000F),
        NETWORK_INFORMATION_TABLE(0x0010, 0x0010),
        SERVICE_DESCRIPTION_TABLE(0x0011, 0x0011),
        EVENT_INFORMATION_TABLE(0x0012, 0x0012),
        RUNNING_STATUS_TABLE(0x0013, 0x0013),
        TIME_DATE_TABLE(0x0014, 0x0014),
        RESERVED2(0x0015, 0x001F),
        AUDIO_VIDEO_PRIVATE_DATA(0x0020, 0x1FFE),
        NONE(0xFFFF, 0xFFFF);

        private final int lowVal;
        private final int highVal;

        PacketType(int lowVal, int highVal) {
            this.lowVal = lowVal;
            this.highVal = highVal;
        }

        public static PacketType valueOf(int pid) {
            for (PacketType type : values()) {
                if (type.lowVal <= pid && pid <= type.highVal) {
                    return type;
                }
            }
            return NONE;
        }
    }

    private static class Header {
        private int length;
        private int sync;
        private boolean hasTransportError;
        private boolean isPayloadStart;
        private boolean isPriority;
        private int pid;
        private boolean isScrambled;
        private boolean hasAdaptationField;
        private boolean hasPayloadData;
        private int counter;

        public Header(int bits) {
            initFromBits(bits);
        }

        private void initFromBits(int bits) {
            sync = (bits & 0xFF000000) >> 24;
            hasTransportError = ((bits & 0x00800000) >> 23) == 1;
            isPayloadStart = ((bits & 0x00400000) >> 22) == 1;
            isPriority = ((bits & 0x00200000) >> 21) == 1;
            pid = (bits & 0x001FFF00) >> 8;
            isScrambled = (bits & 0x000000C0) != 0;
            hasAdaptationField = (bits & 0x00000020) == 0x20;
            hasPayloadData = (bits & 0x00000010) == 0x10;
            counter = (bits & 0x0000000F);
        }

        public void setLength(int val) {
            if (val > TransportStream.TS_FRAME_SIZE || val <= 0) {
                throw new IllegalArgumentException("Invalid header length: " + val);
            }
            length = val;
        }

        public int getLength() {
            return length;
        }

        public boolean isValid() {
            return sync == 0x47;
        }

        public boolean hasTransportError() {
            return hasTransportError;
        }

        public boolean isPayloadStart() {
            return isPayloadStart;
        }

//        public boolean isPriority() {
//            return isPriority;
//        }

        public int getPID() {
            return pid;
        }

        public PacketType getType() {
            return PacketType.valueOf(pid);
        }

        public boolean isScrambled() {
            return isScrambled;
        }

//        public boolean isHasAdaptationField() {
//            return hasAdaptationField;
//        }

//        public boolean isHasPayloadData() {
//            return hasPayloadData;
//        }

//        public int getCounter() {
//            return counter;
//        }

        @Override
        public String toString() {
            return "Header{" +
                    "length=" + length +
                    ", sync=" + sync +
                    ", hasTransportError=" + hasTransportError +
                    ", isPayloadStart=" + isPayloadStart +
                    ", isPriority=" + isPriority +
                    String.format(", pid=0x%04x", pid) +
                    ", isScrambled=" + isScrambled +
                    ", hasAdaptationField=" + hasAdaptationField +
                    ", hasPayloadData=" + hasPayloadData +
                    ", counter=" + counter +
                    '}';
        }
    }

    private static class AdaptationField {
        private boolean isDiscontinuity;
        private boolean isRandomAccess;
        private boolean isPriority;
        private boolean isPcr;
        private boolean isOpcr;
        private boolean isSplicePoint;
        private boolean isPrivate;
        private boolean isExtension;

        public AdaptationField(int bits) {
            initFromBits(bits);
        }

        private void initFromBits(int bits) {
            isDiscontinuity = (bits & 0x80) == 0x80;
            isRandomAccess = (bits & 0x40) == 0x40;
            isPriority = (bits & 0x20) == 0x20;
            isPcr = (bits & 0x10) == 0x10;
            isOpcr = (bits & 0x08) == 0x08;
            isSplicePoint = (bits & 0x04) == 0x04;
            isPrivate = (bits & 0x02) == 0x02;
            isExtension = (bits & 0x01) == 0x01;
        }

        public boolean isPrivate() {
            return isPrivate;
        }

        @Override
        public String toString() {
            return "AdaptationField{" +
                    "isDiscontinuity=" + isDiscontinuity +
                    ", isRandomAccess=" + isRandomAccess +
                    ", isPriority=" + isPriority +
                    ", isPcr=" + isPcr +
                    ", isOpcr=" + isOpcr +
                    ", isSplicePoint=" + isSplicePoint +
                    ", isPrivate=" + isPrivate +
                    ", isExtension=" + isExtension +
                    '}';
        }
    }
}
