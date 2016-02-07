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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

class TransportStream extends Stream {
    private final TuringDecoder turingDecoder;
    private final StreamType type;
    private final ByteBuffer pesBuffer;
    private final byte[] pesBufferArray;
    private int nextPacketPesOffset;
    private boolean decryptingPaused;
    private PesHeader lastPesHeader;

    private final static Logger logger = LoggerFactory.getLogger(TransportStream.class);

    public static final int FRAME_SIZE = 188;

    public TransportStream(TuringDecoder decoder) {
        super();
        this.turingDecoder = decoder;
        this.type = StreamType.NONE;
        pesBufferArray = new byte[FRAME_SIZE];
        pesBuffer = ByteBuffer.wrap(pesBufferArray);
        lastPesHeader = new PesHeader();
    }

    public TransportStream(TuringDecoder decoder, StreamType type) {
        super();
        this.turingDecoder = decoder;
        this.type = type;
        pesBufferArray = new byte[FRAME_SIZE];
        pesBuffer = ByteBuffer.wrap(pesBufferArray);
        lastPesHeader = new PesHeader();
    }

    /**
     * Update the @turingKey and re-enabled decryption
     */
    public void setKey(byte[] val) {
        if (!decryptingPaused) {
            turingKey = val;
        }
    }

    /**
     * Tell the stream not to decrypt another packet until the @turingKey changes
     */
    public void pauseDecrypting() {
        decryptingPaused = true;
    }

    public void resumeDecrypting() {
        decryptingPaused = false;
    }

    public StreamType getType() {
        return type;
    }

    /**
     * Process @packet's headers and decrypt it if necessary.
     *
     * @return The byte array to write to the output stream
     */
    public byte[] processPacket(TransportStreamPacket packet) {
        try {
            copyPayloadToPesBuffer(packet);
            calculatePesHeaderOffset(packet);
        } catch (RuntimeException e) {
            logger.error("Exception while calculating PES header offset: ", e);
            logger.info("{}", packet);
            logger.info("Packet data:\n{}", TivoDecoder.bytesToHexString(packet.getBytes()));
            logger.info("PES buffer:\n{}", TivoDecoder.bytesToHexString(pesBufferArray, 0, pesBuffer.limit()));
            throw e;
        }

        byte[] packetBytes;
        if (!decryptingPaused && packet.needsDecoding()) {
            packetBytes = decryptPacket(packet);
        } else {
            packetBytes = packet.getBytes();
        }

        return packetBytes;
    }

    /**
     * Put @packet's data in a ByteBuffer for easier consumption. If we already know the PES header is too long
     * to end in this packet, don't bother copying @packet's data.
     */
    private void copyPayloadToPesBuffer(TransportStreamPacket packet) {
        byte[] data = packet.getData();
        if (nextPacketPesOffset < data.length) {
            // The PES header might end in this packet
            System.arraycopy(data, nextPacketPesOffset, pesBufferArray, 0, data.length - nextPacketPesOffset);
            pesBuffer.position(0);
            pesBuffer.limit(data.length);
        }
    }

    /**
     * Figure out the PES header offset for @packet. We don't decryptBuffer PES headers, so we need to know exactly
     * where in the buffer to start the decrypt process. If the header extends past the boundary of @packet,
     * store extra length in @nextPacketOffset so the next packet can be decrypted at the right offset.
     * If @nextPacketOffset is larger than this packet's payload, don't try to parse its PES headers.
     */
    private void calculatePesHeaderOffset(TransportStreamPacket packet) {
        int payloadLength = packet.getPayloadLength();
        if (nextPacketPesOffset < payloadLength) {
            int packetPesOffset = nextPacketPesOffset;
            int sumOfPesHeaderLengths = packetPesOffset;
            if (sumOfPesHeaderLengths > 0 || packet.isPayloadStart() || !lastPesHeader.isFinished()) {
                // Only get PES header length if we know this is a payload start, or our prior header extended into it
                sumOfPesHeaderLengths += getPesHeaderLength();
            }

            if (sumOfPesHeaderLengths <= pesBuffer.limit()) {
                // PES headers end in this packet
                packet.setPesHeaderOffset(sumOfPesHeaderLengths);
                nextPacketPesOffset = 0;
            } else {
                nextPacketPesOffset = sumOfPesHeaderLengths - pesBuffer.limit();
                packet.setPesHeaderOffset(pesBuffer.limit());
            }
        } else {
            // We already know the PES header extends into the next packet, so skip over this one
            nextPacketPesOffset -= payloadLength;
            packet.setPesHeaderOffset(payloadLength);
        }
    }

    private int getPesHeaderLength() {
        PesHeader pesHeader;
        if (lastPesHeader.isFinished()) {
            pesHeader = PesHeader.createFrom(pesBuffer);
        } else {
            pesHeader = PesHeader.createFrom(pesBuffer, lastPesHeader.getUnfinishedStartCode(), lastPesHeader.getTrailingZeroBits());
        }
        lastPesHeader = pesHeader;
        return pesHeader.size();
    }

    public boolean decryptBuffer(byte[] buffer) {
        if (doHeader()) {
            // Only decrypt the buffer if the stream's key has been set
            TuringStream turingStream = turingDecoder.prepareFrame(streamId, turingBlockNumber);
            turingDecoder.decryptBytes(turingStream, buffer);
        } else {
            return false;
        }

        return true;
    }

    private byte[] decryptPacket(TransportStreamPacket packet) {
        packet.clearScrambled();
        byte[] encryptedData = packet.getData();
        int encryptedLength = encryptedData.length - packet.getPesHeaderOffset();
        byte[] data = new byte[encryptedLength];
        System.arraycopy(encryptedData, packet.getPesHeaderOffset(), data, 0, encryptedLength);
//        logger.debug("Data to decrypt:\n{}", TivoDecoder.bytesToHexString(data));
        if (!decryptBuffer(data)) {
            logger.error(String.format("Decrypting packet in stream 0x%04x failed", packet.getPID()));
        }
//        logger.debug("Decrypted data:\n{}", TivoDecoder.bytesToHexString(data));
        return packet.getScrambledBytes(data);
    }

    public enum StreamType {
        AUDIO,
        VIDEO,
        PRIVATE_DATA,
        OTHER,
        NOT_IN_PMT,
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

            // Allow us to track packet streams that weren't in the PMT
            typeMap.put(0xffff, NOT_IN_PMT);

            typeMap.put(0x00, NONE);
        }

        public static StreamType valueOf(int val) {
            return typeMap.getOrDefault(val, NONE);
//            }
        }
    }
}
