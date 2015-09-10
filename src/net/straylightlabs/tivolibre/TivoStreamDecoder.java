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
import java.util.HashMap;
import java.util.Map;

abstract class TivoStreamDecoder {
    protected final TuringDecoder turingDecoder;
    protected final int mpegOffset;
    protected final CountingDataInputStream inputStream;
    protected final OutputStream outputStream;
    protected final Map<Integer, TransportStream> streams;

    protected long packetCounter;
    protected PatData patData;

    protected TivoStreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
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

    protected void advanceToMpegOffset() throws IOException {
        int bytesToSkip = (int) (mpegOffset - inputStream.getPosition());
        if (bytesToSkip < 0) {
            TivoDecoder.logger.severe(
                    String.format("Error: Transport stream advanced past MPEG data (MPEG at %d, current position = %d)%n",
                            mpegOffset, inputStream.getPosition()));
        }
        inputStream.skipBytes(bytesToSkip);
    }

    abstract boolean process();

    protected boolean processPatPacket(TransportStreamPacket packet) {
        if (packet.isPayloadStart()) {
            // Advance past pointer field
            packet.advanceDataOffset(1);
        }

        if (packet.readUnsignedByteFromData() != 0) {
            TivoDecoder.logger.severe("PAT Table ID must be 0x00");
            return false;
        }

        int patField = packet.readUnsignedShortFromData();
        int sectionLength = patField & 0x0fff;

        if ((patField & 0xC000) != 0x8000) {
            TivoDecoder.logger.severe("Failed to validate PAT Misc field");
            return false;
        }
        if ((patField & 0x0C00) != 0x0000) {
            TivoDecoder.logger.severe("Failed to validate PAT MBZ of section length");
            return false;
        }

        int streamId = packet.readUnsignedShortFromData();
        sectionLength -= 2;

        patData.setVersionNumber(packet.readUnsignedByteFromData() & 0x3E);
        sectionLength--;
        patData.setSectionNumber(packet.readUnsignedByteFromData());
        sectionLength--;
        patData.setLastSectionNumber(packet.readUnsignedByteFromData());
        sectionLength--;

        sectionLength -= 4; // Ignore the CRC

        while (sectionLength > 0) {
            int programNumber = packet.readUnsignedShortFromData();
            sectionLength -= 2;

            patField = packet.readUnsignedShortFromData();
            sectionLength -= 2;
            patData.setProgramMapPid(patField & 0x1fff);

            // Create a stream for this PID unless one already exists
            if (!streams.containsKey(patData.getProgramMapPid())) {
                TivoDecoder.logger.info(String.format("Creating a new stream for PMT PID 0x%04x", patData.getProgramMapPid()));
                TransportStream stream = new TransportStream(outputStream, turingDecoder);
                streams.put(patData.getProgramMapPid(), stream);
            }
        }
        if (sectionLength < 0) {
            TivoDecoder.logger.severe("Problem parsing PAT: advanced too far in the data stream");
            return false;
        }

        return true;
    }

    protected static class PatData {
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
