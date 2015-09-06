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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

class TransportStream {
    private final TuringDecoder turingDecoder;
    //    private final int pid;
    private int streamId;
    private StreamType type;
    private byte[] pesBuffer;
    private byte[] turingKey;
    private int turingBlockNumber;
    //    private int turingCrypted;
    private final Deque<TransportStreamPacket> packets;
    private final Deque<Integer> pesHeaderLengths;
    private final OutputStream outputStream;

    public static final int TS_FRAME_SIZE = 188;

    public TransportStream(OutputStream outputStream, TuringDecoder decoder) {
//        this.pid = pid;
        this.type = StreamType.NONE;
        this.outputStream = outputStream;
        this.turingDecoder = decoder;
        pesBuffer = new byte[TS_FRAME_SIZE * 10];
        packets = new ArrayDeque<>();
        pesHeaderLengths = new ArrayDeque<>();
    }

    public TransportStream(OutputStream outputStream, TuringDecoder decoder, StreamType type) {
        this(outputStream, decoder);
        this.type = type;
    }

    public void setStreamId(int val) {
        streamId = val;
    }

    public StreamType getType() {
        return type;
    }

    public void setKey(byte[] val) {
        turingKey = val;
    }

    public boolean addPacket(TransportStreamPacket packet) {
        boolean flushBuffers = false;

        // If this packet's Payload Unit Start Indicator is set,
        // or one of the stream's previous packet's was set, we
        // need to buffer the packet, such that we can make an
        // attempt to determine where the end of the PES headers
        // lies.   Only after we've done that, can we determine
        // the packet offset at which decryption is to occur.
        // The accounts for the situation where the PES headers
        // straddles two packets, and decryption is needed on the 2nd.
        if (packet.isPayloadStart() || packets.size() != 0) {
            packets.addLast(packet);

            // Form one contiguous buffer containing all buffered packet payloads
//            Arrays.fill(pesBuffer, (byte) 0);
            int pesBufferLen = 0;
            for (TransportStreamPacket p : packets) {
                ByteBuffer data = p.getData();
                data.get(pesBuffer, pesBufferLen, data.capacity());
                pesBufferLen += data.capacity();
            }

            // Scan the contiguous buffer for PES headers
            // in order to find the end of PES headers.
            pesHeaderLengths.clear();
            if (!getPesHeaderLength(pesBuffer)) {
                TivoDecoder.logger.severe(String.format("Failed to parse PES headers for packet %d%n", packet.getPacketId()));
                return false;
            }
            int pesHeaderLength = pesHeaderLengths.stream().mapToInt(i -> i).sum() / 8;
//            System.out.format("pesDecodeBufferLen %d, pesHeaderLength %d%n", pesBufferLen, pesHeaderLength);

            // Do the PES headers end in this packet ?
            if (pesHeaderLength < pesBufferLen) {
                flushBuffers = true;

                // For each packet, set the end point for PES headers in that packet
                for (TransportStreamPacket p : packets) {
                    while (!pesHeaderLengths.isEmpty()) {
                        int headerLen = pesHeaderLengths.removeFirst() / 8;
                        if (headerLen + p.getPayloadOffset() + p.getPesHeaderOffset() < TS_FRAME_SIZE) {
                            p.setPesHeaderOffset(p.getPesHeaderOffset() + headerLen);
                            pesHeaderLength -= headerLen;
                        } else {
                            // Packet boundary occurs within this PES header
                            // Three cases to handle :
                            //   1. pkt boundary falls within startCode
                            //        start decrypt after startCode finish in NEXT pkt
                            //   2. pkt boundary falls between startCode and payload
                            //        start decrypt at payload start in NEXT pkt
                            //   3. pkt boundary falls within payload
                            //        start decrypt offset into the payload
                            int packetBoundaryOffset = TS_FRAME_SIZE - p.getPayloadOffset() - p.getPesHeaderOffset();
                            if (packetBoundaryOffset < 4) {
                                headerLen -= packetBoundaryOffset;
                                headerLen *= 8;
                                pesHeaderLengths.addFirst(headerLen);
                            }
                            break;
                        }
                    }
                }
            }

        } else {
            flushBuffers = true;
            packets.addLast(packet);
        }

        if (flushBuffers) {
//            System.out.println("Flush packets for write");

            // Loop through each buffered packet.
            // If it is encrypted, perform decryption and then write it out.
            // Otherwise, just write it out.
            try {
                while (!packets.isEmpty()) {
                    TransportStreamPacket p = packets.removeFirst();
                    byte[] packetBytes;
                    if (p.isScrambled()) {
                        p.clearScrambled();
                        ByteBuffer encryptedData = p.getData();
//                        System.out.println("encrypted buffer:");
//                        for (int i = 0; i < encryptedData.capacity(); i++) {
//                            System.out.format("%02x", encryptedData.get(i));
//                            if ((i+1) % 40 == 0)
//                                System.out.format("%n");
//                        }
//                        System.out.println();
//                        System.out.format("PesHeaderOffset: %d, PayloadOffset: %d, PayloadLength: %d%n",
//                                p.getPesHeaderOffset(), p.getPayloadOffset(), encryptedData.capacity());
                        int encryptedLength = encryptedData.capacity() - p.getPesHeaderOffset();
//                        System.out.format("Decrypting PktID %d : decrypt offset %d len %d%n",
//                                p.getPacketId(), p.getPayloadOffset() + p.getPesHeaderOffset(), encryptedLength);
                        byte[] data = new byte[encryptedLength];
                        for (int i = 0; i < p.getPesHeaderOffset(); i++) {
                            encryptedData.get(); // Advance past PES header
                        }
                        encryptedData.get(data);
                        if (!decrypt(data)) {
                            TivoDecoder.logger.severe("Decrypting packet failed");
                            return false;
                        }
                        packetBytes = p.getScrambledBytes(data);
                    } else {
                        packetBytes = p.getBytes();
                    }

//                    System.out.println("payload offset: " + p.getPayloadOffset());
//                    StringBuilder sb = new StringBuilder();
//                    int counter = 0;
//                    for (byte b : packetBytes) {
//                        sb.append(String.format("%02x", b));
//                        if (++counter % 40 == 0)
//                            sb.append("\n    ");
//                    }
//                    System.out.println("writing buffer:\n    " + sb.toString());
                    outputStream.write(packetBytes);
                }
            } catch (Exception e) {
                TivoDecoder.logger.severe("Error writing file: " + e.getLocalizedMessage());
                return false;
            }
        }

        return true;
    }

    private boolean getPesHeaderLength(byte[] buffer) {
        MpegParser parser = new MpegParser(buffer);
        boolean done = false;
        while (!done && !parser.isEOF()) {
            int headerOffset = parser.nextBits(8);
//            System.out.format("PES header offset: 0x%x%n", headerOffset);
//            parser.advanceBits(8); // Skip header offset

            if (0x000001 != parser.nextBits(24)) {
//                System.out.println("done!");
                done = true;
                continue;
            }

            int len = 0;
            int startCode = parser.nextBits(32);
            parser.clear();
            switch (MpegParser.ControlCode.valueOf(startCode)) {
                case EXTENSION_START_CODE:
//                    System.out.println("EXTENSION_START_CODE");
                    len = parser.extensionHeader();
                    break;
                case GROUP_START_CODE:
//                    System.out.println("GROUP_START_CODE");
                    len = parser.groupOfPicturesHeader();
                    break;
                case USER_DATA_START_CODE:
//                    System.out.println("USER_DATA_START_CODE");
                    len = parser.userData();
                    break;
                case PICTURE_START_CODE:
//                    System.out.println("PICTURE_START_CODE");
                    len = parser.pictureHeader();
                    break;
                case SEQUENCE_HEADER_CODE:
//                    System.out.println("SEQUENCE_HEADER_CODE");
                    len = parser.sequenceHeader();
                    break;
                case SEQUENCE_END_CODE:
//                    System.out.println("SEQUENCE_END_CODE");
                    len = parser.sequenceEnd();
                    break;
                case ANCILLARY_DATA_CODE:
//                    System.out.println("ANCILLARY_DATA_CODE");
                    len = parser.ancillaryData();
                    break;
                default:
//                    System.out.format("startCode = 0x%03x%n", startCode);
                    if (startCode >= 0x101 && startCode <= 0x1AF) {
                        done = true;
                    } else if ((startCode == 0x1BD) || (startCode >= 0x1C0 && startCode <= 0x1EF)) {
                        len = parser.pesHeader();
                    } else {
                        TivoDecoder.logger.severe(String.format("Error: Unhandled PES header: 0x%08x", startCode));
                        return false;
                    }
            }

            if (len > 0) {
//                System.out.format("Adding header len (%d)%n", len);
                pesHeaderLengths.addLast(len);
            }
        }

        return true;
    }

    public boolean decrypt(byte[] buffer) {
//        System.out.print("Turing key before doHeader(): ");
//        for (int i = 0; i < 16; i++) {
//            System.out.format("%02x", turingKey[i]);
//        }
//        System.out.println();

        if (!doHeader()) {
            TivoDecoder.logger.severe("Problem parsing Turing header");
            return false;
        }
//        StringBuilder sb = new StringBuilder();
//        sb.append(String.format("BBB : stream_id 0x%02x, blockno %d%n", streamId, turingBlockNumber));
        TuringStream turingStream = turingDecoder.prepareFrame(streamId, turingBlockNumber);
//        sb.append(String.format("CCC : stream_id 0x%02x, blockno %d, crypted 0x%08x%n", streamId, turingBlockNumber, turingCrypted));
//        System.out.print("Turing key before decryptBytes(): ");
//        for (int i = 0; i < 16; i++) {
//            System.out.format("%02x", turingKey[i]);
//        }
//        System.out.println();
        turingDecoder.decryptBytes(turingStream, buffer);
//        sb.append(String.format("Buffer: "));
//        for (byte b : buffer) {
//            sb.append(String.format("%02X", b));
//        }
//        sb.append(String.format("%n"));
//        System.out.println(sb.toString());
        return true;
    }

    private boolean doHeader() {
        boolean noProblems = true;

        if ((turingKey[0] & 0x80) == 0)
            noProblems = false;

        if ((turingKey[1] & 0x40) == 0)
            noProblems = false;

        turingBlockNumber = (turingKey[0x1] & 0x3f) << 0x12;
        turingBlockNumber |= (turingKey[0x2] & 0xff) << 0xa;
        turingBlockNumber |= (turingKey[0x3] & 0xc0) << 0x2;

        if ((turingKey[3] & 0x20) == 0)
            noProblems = false;

        turingBlockNumber |= (turingKey[0x3] & 0x1f) << 0x3;
        turingBlockNumber |= (turingKey[0x4] & 0xe0) >> 0x5;


        if ((turingKey[4] & 0x10) == 0)
            noProblems = false;

//        turingCrypted = (turingKey[0xb] & 0x03) << 0x1e;
//        turingCrypted |= (turingKey[0xc] & 0xff) << 0x16;
//        turingCrypted |= (turingKey[0xd] & 0xfc) << 0xe;

        if ((turingKey[0xd] & 0x2) == 0)
            noProblems = false;

//        turingCrypted |= (turingKey[0xd] & 0x01) << 0xf;
//        turingCrypted |= (turingKey[0xe] & 0xff) << 0x7;
//        turingCrypted |= (turingKey[0xf] & 0xfe) >> 0x1;

        if ((turingKey[0xf] & 0x1) == 0)
            noProblems = false;

        return noProblems;
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
