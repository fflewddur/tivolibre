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
    private ByteBuffer headerBuffer;
    private ByteBuffer data;
    private int dataOffset;
    private int pesHeaderOffset;

    public boolean readFrom(CountingDataInputStream inputStream) throws IOException {
        header = readHeader(inputStream);
        if (!header.isValid()) {
            TivoDecoder.logger.severe("Invalid TS packet header");
            throw new IOException("Invalid TS packet header");
        }

        // Read the rest of the packet
        int bytesToRead = TransportStream.TS_FRAME_SIZE - header.getLength();
        int bytesRead, totalBytesRead = 0;
        byte[] buffer = new byte[bytesToRead];

        do {
            bytesRead = inputStream.read(buffer, totalBytesRead, bytesToRead - totalBytesRead);
            totalBytesRead += bytesRead;
        } while (bytesRead != -1 && bytesToRead > 0 && totalBytesRead < bytesToRead);
        data = ByteBuffer.wrap(buffer);
        dataOffset = 0;

        if (totalBytesRead != bytesToRead) {
            TivoDecoder.logger.severe(String.format("Only read %d of %d bytes", totalBytesRead, bytesToRead));
        }
        return (totalBytesRead == bytesToRead);
    }

    private Header readHeader(CountingDataInputStream inputStream) throws IOException {
        int headerLength = Integer.BYTES;
        int headerBits = inputStream.readInt();
        int adaptationFieldLength = 0;
        int adaptationFieldBits = 0;
        header = new Header(headerBits);
        if (header.hasAdaptationField) {
            adaptationFieldLength = inputStream.readUnsignedByte();
            adaptationFieldBits = inputStream.readByte();
//            System.out.println("Adaptation field length = " + adaptationFieldLength);
            headerLength += (adaptationFieldLength + 1);
        }
        headerBuffer = ByteBuffer.allocate(headerLength);
        headerBuffer.putInt(headerBits);
        if (header.hasAdaptationField) {
            headerBuffer.put((byte) adaptationFieldLength);
            headerBuffer.put((byte) adaptationFieldBits);
            for (int i = 0; i < adaptationFieldLength - 1; i++) {
                byte b = inputStream.readByte();
                headerBuffer.put(b);
            }
        }
        header.setLength(headerLength);

        return header;
    }

    public byte[] getBytes() {
        byte[] buffer = new byte[TransportStream.TS_FRAME_SIZE];
        if (isScrambled()) {
            throw new IllegalStateException("Cannot get bytes from scrambled packet");
        }
        if (headerBuffer.hasArray() && data.hasArray()) {
            System.arraycopy(headerBuffer.array(), 0, buffer, 0, header.getLength());
            System.arraycopy(data.array(), 0, buffer, header.getLength(), data.capacity());
        } else {
            throw new IllegalStateException("Cannot get bytes from empty packet");
        }
        return buffer;
    }

    public byte[] getScrambledBytes(byte[] decrypted) {
        byte[] buffer = new byte[TransportStream.TS_FRAME_SIZE];
        if (headerBuffer.hasArray()) {
            System.arraycopy(headerBuffer.array(), 0, buffer, 0, header.getLength());
            System.arraycopy(data.array(), 0, buffer, header.getLength(), TransportStream.TS_FRAME_SIZE - decrypted.length - header.getLength());
            System.arraycopy(decrypted, 0, buffer, header.getLength() + getPesHeaderOffset(), decrypted.length);
        } else {
            throw new IllegalStateException("Cannot get bytes from empty packet");
        }
        return buffer;
    }

    public boolean isScrambled() {
        return header.isScrambled();
    }

    public void clearScrambled() {
        byte[] headerBytes = headerBuffer.array();
        headerBytes[3] &= ~(0xC0);
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

    /**
     * Returns a read-only view of the @data ByteBuffer, initialized to the start of the buffer.
     */
    public ByteBuffer getData() {
        ByteBuffer readOnlyView = data.asReadOnlyBuffer();
        readOnlyView.rewind();
        return readOnlyView;
    }

    public byte[] readBytesFromData(int length) {
        byte[] buffer = new byte[length];
        for (int i = 0; i < length; i++) {
            buffer[i] = data.get(dataOffset++);
        }
        return buffer;
    }

    public int readIntFromData() {
        int val = data.getInt(dataOffset);
        dataOffset += 4;
        return val;
    }

    public int readUnsignedByteFromData() {
        int val = data.get(dataOffset) & 0xff; // Treat as unsigned byte
        dataOffset += 1;
        return val;
    }

    public int readUnsignedShortFromData() {
        int val = data.getShort(dataOffset) & 0xffff; // Treat as unsigned short
        dataOffset += 2;
        return val;
    }

    public void advanceDataOffset(int bytes) {
        dataOffset += bytes;
    }

    public void setIsPmt(boolean val) {
        isPmt = val;
    }

    public boolean isPmt() {
        return isPmt;
    }

    public void setIsTivo(boolean val) {
        isTivo = val;
    }

    public boolean isTivo() {
        return isTivo;
    }

    @Override
    public String toString() {
        return String.format("====== Packet: %d ======", packetId);
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
            sync = (bits & 0xff000000) >> 24;
            hasTransportError = ((bits & 0x00800000) >> 23) == 1;
            isPayloadStart = ((bits & 0x00400000) >> 22) == 1;
            isPriority = ((bits & 0x00200000) >> 21) == 1;
            pid = (bits & 0x001FFF00) >> 8;
            isScrambled = ((bits & 0x000000C0) >> 6) != 0;
            hasAdaptationField = ((bits & 0x00000020) >> 5) == 1;
            hasPayloadData = ((bits & 0x00000010) >> 4) == 1;
            counter = (bits & 0x0000000F);
        }

        public void setLength(int val) {
            length = val;
        }

        public int getLength() {
            return length;
        }

        public boolean isValid() {
            return sync == 0x47;
        }

        public boolean isHasTransportError() {
            return hasTransportError;
        }

        public boolean isPayloadStart() {
            return isPayloadStart;
        }

        public boolean isPriority() {
            return isPriority;
        }

        public int getPID() {
            return pid;
        }

        public PacketType getType() {
            return PacketType.valueOf(pid);
        }

        public boolean isScrambled() {
            return isScrambled;
        }

        public boolean isHasAdaptationField() {
            return hasAdaptationField;
        }

        public boolean isHasPayloadData() {
            return hasPayloadData;
        }

        public int getCounter() {
            return counter;
        }
    }

    private static class AdaptationField {

    }
}