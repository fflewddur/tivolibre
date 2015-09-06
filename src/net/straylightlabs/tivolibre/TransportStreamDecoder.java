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

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

class TransportStreamDecoder implements TivoStreamDecoder {
    private final TuringDecoder turingDecoder;
    private final int mpegOffset;
    private final CountingDataInputStream inputStream;
    private final OutputStream outputStream;
    private final Map<Integer, TransportStream> streams;

    private long packetCounter;
    private PatData patData;

    public TransportStreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
                                  OutputStream outputStream) {
        this.turingDecoder = decoder;
        this.mpegOffset = mpegOffset;
        this.inputStream = inputStream;
        this.outputStream = outputStream;

        packetCounter = 0;
        patData = new PatData();
        streams = new HashMap<>();
        initPatStream();
    }

    private void initPatStream() {
//        System.out.format("Creating new stream for PID (0x%04x)%n", 0);
        TransportStream stream = new TransportStream(outputStream, turingDecoder);
        streams.put(0, stream);
    }

    @Override
    public boolean process() {
        try {
            advanceToMpegOffset();
            TransportStreamPacket packet = new TransportStreamPacket();
            while (packet.readFrom(inputStream)) {
                packet.setPacketId(++packetCounter);
                if (packetCounter % 10000 == 0) {
                    TivoDecoder.logger.info(
                            String.format("PacketId: %d Total bytes read from pipe: %d", packetCounter, inputStream.getPosition())
                    );
                }
//                System.out.println(packet);
                switch (packet.getPacketType()) {
                    case PROGRAM_ASSOCIATION_TABLE:
                        if (!processPatPacket(packet)) {
                            TivoDecoder.logger.severe("Error processing PAT packet");
                            return false;
                        }
                        break;
                    case AUDIO_VIDEO_PRIVATE_DATA:
                        if (packet.getPID() == patData.getProgramMapPid()) {
                            packet.setIsPmt(true);
                            if (!processPmtPacket(packet)) {
                                TivoDecoder.logger.severe("Error processing PMT packet");
                                return false;
                            }
                        } else {
                            TransportStream stream = streams.get(packet.getPID());
                            if (stream != null && stream.getType() == TransportStream.StreamType.PRIVATE_DATA) {
                                packet.setIsTivo(true);
                            }

                            if (packet.isTivo()) {
                                if (!processTivoPacket(packet)) {
                                    TivoDecoder.logger.severe("Error processing TiVo packet");
                                    return false;
                                }
                            }
                        }
                        break;
                    default:
                        TivoDecoder.logger.severe("Unknown packet type");
                        return false;
                }
                TransportStream stream = streams.get(packet.getPID());
                if (stream == null) {
                    TivoDecoder.logger.severe(String.format("Error: No TransportStream exists with PID 0x%04x", packet.getPID()));
                    return false;
                }
                if (!stream.addPacket(packet)) {
                    return false;
                }

//                if (packet.getPacketId() > 50000) {
//                    return false;
//                }
                packet = new TransportStreamPacket();
            }
            TivoDecoder.logger.info("Successfully read packets");
            return true;
        } catch (EOFException e) {
            TivoDecoder.logger.info("End of file reached");
            return true;
        } catch (IOException e) {
            TivoDecoder.logger.severe("Error reading transport stream: " + e.getLocalizedMessage());
        }

        return false;
    }

    private void advanceToMpegOffset() throws IOException {
        int bytesToSkip = (int) (mpegOffset - inputStream.getPosition());
        if (bytesToSkip < 0) {
            TivoDecoder.logger.severe(
                    String.format("Error: Transport stream advanced past MPEG data (MPEG at %d, current position = %d)%n",
                            mpegOffset, inputStream.getPosition()));
        }
        inputStream.skipBytes(bytesToSkip);
    }

    private boolean processPatPacket(TransportStreamPacket packet) {
        if (packet.isPayloadStart()) {
            // Advance past pointer field
            packet.advanceDataOffset(1);
        }

        if (packet.readUnsignedByteFromData() != 0) {
            TivoDecoder.logger.severe("PAT Table ID must be 0x00");
            return false;
        }

        int patField = packet.readUnsignedShortFromData();
        int sectionLength = patField & 0x03ff;
        if ((patField & 0xC000) != 0x8000) {
            TivoDecoder.logger.severe("Failed to validate PAT Misc field");
            return false;
        }
        if ((patField & 0x0C00) != 0x0000) {
            TivoDecoder.logger.severe("Failed to validate PAT MBZ of section length");
            return false;
        }

        packet.readUnsignedShortFromData();
        sectionLength -= 2;

        patData.setVersionNumber(packet.readUnsignedByteFromData() & 0x3E);
        sectionLength--;
        patData.setSectionNumber(packet.readUnsignedByteFromData());
        sectionLength--;
        patData.setLastSectionNumber(packet.readUnsignedByteFromData());
        sectionLength--;

        sectionLength -= 4; // Ignore the CRC
        while (sectionLength > 0) {
            packet.readUnsignedShortFromData();
            sectionLength -= 2;

            // Again?
            patField = packet.readUnsignedShortFromData();
            sectionLength -= 2;
            patData.setProgramMapPid(patField & 0x1fff);

            // Create a stream for this PID unless one already exists
            if (!streams.containsKey(patData.getProgramMapPid())) {
//                System.out.format("Creating a new stream for PMT PID 0x%04x%n", patData.getProgramMapPid());
                TransportStream stream = new TransportStream(outputStream, turingDecoder);
                streams.put(patData.getProgramMapPid(), stream);
            }
        }

        return true;
    }

    private boolean processPmtPacket(TransportStreamPacket packet) {
//        System.out.println("PMT packet");

        if (packet.isPayloadStart()) {
            // Advance past pointer field
            packet.advanceDataOffset(1);
        }

        // Advance past table_id field
        packet.advanceDataOffset(1);

        int pmtField = packet.readUnsignedShortFromData();
        int sectionLength = pmtField & 0x0fff;

        // Advance past program/section/next numbers
        packet.advanceDataOffset(9);
        sectionLength -= 9;

        // Ignore the CRC
        sectionLength -= 4;

        while (sectionLength > 0) {
            int streamTypeId = packet.readUnsignedByteFromData();
            TransportStream.StreamType streamType = TransportStream.StreamType.valueOf(streamTypeId);

            // Advance past stream_type field
            sectionLength--;

            pmtField = packet.readUnsignedShortFromData();
            int streamPid = pmtField & 0x1fff;
            sectionLength -= 2;

            pmtField = packet.readUnsignedShortFromData();
            int esInfoLength = pmtField & 0x1fff;
            sectionLength -= 2;
            // Advance past ES info
            packet.advanceDataOffset(esInfoLength);
            sectionLength -= esInfoLength;

            // Create a stream for this PID unless one already exists
            if (!streams.containsKey(streamPid)) {
//                System.out.format("Creating a new %s stream for PID 0x%04x%n", streamType, streamPid);
                TransportStream stream = new TransportStream(outputStream, turingDecoder, streamType);
                streams.put(streamPid, stream);
            }
        }

        return true;
    }

    private boolean processTivoPacket(TransportStreamPacket packet) {
//        System.out.println("TiVo packet");

        int validator = packet.readIntFromData();
        if (validator != 0x5469566f) {
            TivoDecoder.logger.severe(String.format("Invalid TiVo private data validator: %08x", validator));
            return false;
        }

        // Advance past unknown bytes
        packet.advanceDataOffset(5);

        int streamLength = packet.readUnsignedByteFromData();
        while (streamLength > 0) {
            int packetId = packet.readUnsignedShortFromData();
            streamLength -= 2;
            int streamId = packet.readUnsignedByteFromData();
            streamLength--;
            // Advance past reserved field
            packet.advanceDataOffset(1);
            streamLength--;

            TransportStream stream = streams.get(packetId);
            if (stream == null) {
                TivoDecoder.logger.severe(String.format("No TransportStream with ID 0x%04x found", packetId));
                return false;
            }
            stream.setStreamId(streamId);
            stream.setKey(packet.readBytesFromData(16));
            streamLength -= 16;
        }

        return true;
    }


    private static class PatData {
        private int versionNumber;
        private int currentNextIndicator;
        private int sectionNumber;
        private int lastSectionNumber;
        private int programMapPid;

        public int getVersionNumber() {
            return versionNumber;
        }

        public void setVersionNumber(int versionNumber) {
            this.versionNumber = versionNumber;
        }

        public int getCurrentNextIndicator() {
            return currentNextIndicator;
        }

        public void setCurrentNextIndicator(int currentNextIndicator) {
            this.currentNextIndicator = currentNextIndicator;
        }

        public int getSectionNumber() {
            return sectionNumber;
        }

        public void setSectionNumber(int sectionNumber) {
            this.sectionNumber = sectionNumber;
        }

        public int getLastSectionNumber() {
            return lastSectionNumber;
        }

        public void setLastSectionNumber(int lastSectionNumber) {
            this.lastSectionNumber = lastSectionNumber;
        }

        public int getProgramMapPid() {
            return programMapPid;
        }

        public void setProgramMapPid(int programMapPid) {
            this.programMapPid = programMapPid;
        }
    }
}
