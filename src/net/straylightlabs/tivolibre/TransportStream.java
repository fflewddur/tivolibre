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

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

class TransportStream extends Stream {
    private final OutputStream outputStream;
    private final TuringDecoder turingDecoder;
    private StreamType type;
    private final ByteBuffer pesBuffer;
    private final byte[] pesBufferArray;
    private int nextPacketPesOffset;

    public static final int FRAME_SIZE = 188;

    public TransportStream(OutputStream outputStream, TuringDecoder decoder) {
        super();
        this.outputStream = outputStream;
        this.turingDecoder = decoder;
        this.type = StreamType.NONE;
        pesBufferArray = new byte[FRAME_SIZE];
        pesBuffer = ByteBuffer.wrap(pesBufferArray);
    }

    public TransportStream(OutputStream outputStream, TuringDecoder decoder, StreamType type) {
        this(outputStream, decoder);
        this.type = type;
    }

    public void setKey(byte[] val) {
        turingKey = val;
    }

    public StreamType getType() {
        return type;
    }

    /**
     * Decrypt @packet (if necessary) and write it to our output stream.
     */
    public int writePacket(TransportStreamPacket packet) {
        try {
            copyPayloadToPesBuffer(packet);
            calculatePesHeaderOffset(packet);
        } catch (RuntimeException e) {
            TivoDecoder.logger.severe("Exception while calculating PES header offset: " + e.getLocalizedMessage());
            e.printStackTrace();
            TivoDecoder.logger.info(packet.toString());
            TivoDecoder.logger.info(String.format("Packet data:%n%s", TivoDecoder.bytesToHexString(packet.getBytes())));
            TivoDecoder.logger.info(String.format("PES buffer:%n%s", TivoDecoder.bytesToHexString(pesBufferArray, 0, pesBuffer.limit())));
            System.exit(0);
            throw e;
        }

        return decryptAndWritePacket(packet);
    }

    /**
     * Put @packet's data in a ByteBuffer for easier consumption
     */
    private void copyPayloadToPesBuffer(TransportStreamPacket packet) {
        byte[] data = packet.getData();
        System.arraycopy(data, 0, pesBufferArray, 0, data.length);
        pesBuffer.position(0);
        pesBuffer.limit(data.length);

    }

    /**
     * Figure out the PES header offset for @packet. We don't decrypt PES headers, so we need to know exactly
     * where in the buffer to start the decrypt process. If the header extends past the boundary of @packet,
     * store extra length in @nextPacketOffset so the next packet can be decrypted at the right offset.
     */
    private void calculatePesHeaderOffset(TransportStreamPacket packet) {
        int packetPesOffset = nextPacketPesOffset;
        int sumOfPesHeaderLengths = getPesHeaderLength() + packetPesOffset;
        if (sumOfPesHeaderLengths <= pesBuffer.limit()) {
            // PES headers end in this packet
            packet.setPesHeaderOffset(sumOfPesHeaderLengths);
            nextPacketPesOffset = 0;
        } else {
            nextPacketPesOffset = sumOfPesHeaderLengths - pesBuffer.limit();
            packet.setPesHeaderOffset(pesBuffer.limit());
        }
    }

    private int getPesHeaderLength() {
        PesHeader pesHeader = PesHeader.createFrom(pesBuffer);
        return pesHeader.size();
    }

    public boolean decrypt(byte[] buffer) {
        if (!doHeader()) {
            TivoDecoder.logger.severe("Problem parsing Turing header");
            return false;
        }
        TuringStream turingStream = turingDecoder.prepareFrame(streamId, turingBlockNumber);
        turingDecoder.decryptBytes(turingStream, buffer);
        return true;
    }

    /**
     * If @packet is encrypted, decrypt it and write it out.
     * Otherwise, just write it out.
     */
    private int decryptAndWritePacket(TransportStreamPacket packet) {
        int bytesWritten = 0;
        try {
            byte[] packetBytes;
            if (packet.isScrambled()) {
                packet.clearScrambled();
                byte[] encryptedData = packet.getData();
                int encryptedLength = encryptedData.length - packet.getPesHeaderOffset();
                byte[] data = new byte[encryptedLength];
//                if (packet.getPesHeaderOffset() > 0)
//                    TivoDecoder.logger.info(String.format("Packet: %d PES Header offset: %d", packet.getPacketId(), packet.getPesHeaderOffset()));
                System.arraycopy(encryptedData, packet.getPesHeaderOffset(), data, 0, encryptedLength);
                if (!decrypt(data)) {
                    TivoDecoder.logger.severe("Decrypting packet failed");
                    throw new RuntimeException("Decrypting packet failed");
                }
                packetBytes = packet.getScrambledBytes(data);
            } else {
                packetBytes = packet.getBytes();
            }
            outputStream.write(packetBytes);
            bytesWritten += packetBytes.length;
        } catch (Exception e) {
            TivoDecoder.logger.severe("Error writing file: " + e.getLocalizedMessage());
            throw new RuntimeException();
        }

        return bytesWritten;
    }

    public enum StreamType {
        AUDIO,
        VIDEO,
        PRIVATE_DATA,
        OTHER,
        NONE;

        private static Map<Integer, StreamType> typeMap;

        static {
            typeMap = new HashMap<>();
            typeMap.put(0x01, VIDEO);
            typeMap.put(0x02, VIDEO);
            typeMap.put(0x10, VIDEO);
            typeMap.put(0x1b, VIDEO);
            typeMap.put(0x80, VIDEO);
            typeMap.put(0xea, VIDEO);

            typeMap.put(0x03, AUDIO);
            typeMap.put(0x04, AUDIO);
            typeMap.put(0x11, AUDIO);
            typeMap.put(0x0f, AUDIO);
            typeMap.put(0x81, AUDIO);
            typeMap.put(0x8a, AUDIO);

            typeMap.put(0x08, OTHER);
            typeMap.put(0x0a, OTHER);
            typeMap.put(0x0b, OTHER);
            typeMap.put(0x0c, OTHER);
            typeMap.put(0x0d, OTHER);
            typeMap.put(0x14, OTHER);
            typeMap.put(0x15, OTHER);
            typeMap.put(0x16, OTHER);
            typeMap.put(0x17, OTHER);
            typeMap.put(0x18, OTHER);
            typeMap.put(0x19, OTHER);

            typeMap.put(0x05, OTHER);
            typeMap.put(0x06, OTHER);
            typeMap.put(0x07, OTHER);
            typeMap.put(0x09, OTHER);
            typeMap.put(0x0e, OTHER);
            typeMap.put(0x12, OTHER);
            typeMap.put(0x13, OTHER);
            typeMap.put(0x1a, OTHER);
            typeMap.put(0x7f, OTHER);

            typeMap.put(0x97, PRIVATE_DATA);

            typeMap.put(0x00, NONE);
        }

        public static StreamType valueOf(int val) {
            return typeMap.getOrDefault(val, PRIVATE_DATA);
        }
    }
}
