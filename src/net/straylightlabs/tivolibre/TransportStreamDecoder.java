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

class TransportStreamDecoder extends TivoStreamDecoder {
    public TransportStreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
                                  OutputStream outputStream) {
        super(decoder, mpegOffset, inputStream, outputStream);
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

    private boolean processPmtPacket(TransportStreamPacket packet) {
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
}
