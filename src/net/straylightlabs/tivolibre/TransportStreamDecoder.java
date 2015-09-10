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
import java.nio.ByteBuffer;

class TransportStreamDecoder extends TivoStreamDecoder {
    private ByteBuffer inputBuffer;
    private int extraBufferSize;

    private static final byte SYNC_BYTE_VALUE = 0x47;
    private static final int PACKETS_UNTIL_RESYNC = 5;

    public TransportStreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
                                  OutputStream outputStream) {
        super(decoder, mpegOffset, inputStream, outputStream);
        inputBuffer = ByteBuffer.allocate(TransportStream.FRAME_SIZE);
    }

    @Override
    public boolean process() {
        try {
            advanceToMpegOffset();
            TivoDecoder.logger.info(String.format("Starting TS processing at position %,d", inputStream.getPosition()));

            while (true) {
                fillBuffer();

                TransportStreamPacket packet;
                try {
                    packet = TransportStreamPacket.createFrom(inputBuffer, ++packetCounter);
                } catch (TransportStreamException e) {
                    TivoDecoder.logger.severe(e.getLocalizedMessage());
                    packet = createPacketAtNextSyncByte(++packetCounter);
                    TivoDecoder.logger.info("Re-synched!");
                }

//                System.out.println("Packet contents:\n" + packet.dump());
//                packet.setPacketId(++packetCounter);
//                if (packetCounter > 6000000) {
//                    return false;
//                }
//                if (packetCounter >= 4730000) {
                if (packetCounter % 10000 == 0) {
                    TivoDecoder.logger.info(String.format("PacketId: %,d Type: %s PID: 0x%04x Position after reading: %,d",
                                    packetCounter, packet.getPacketType(), packet.getPID(), inputStream.getPosition())
                    );
                    TivoDecoder.logger.info(packet.toString());
                }
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
                    case NULL:
                        // These packets are for maintaining a constant bit-rate and are meant to be discarded
                        TivoDecoder.logger.info("NULL packet");
                        continue;
                    default:
                        TivoDecoder.logger.warning("Unsupported packet type: " + packet.getPacketType());
//                        return false;
                }

                if (!addPacketToStream(packet)) {
                    return false;
                }
            }
        } catch (EOFException e) {
            TivoDecoder.logger.info("End of file reached");
            return true;
        } catch (IOException e) {
            TivoDecoder.logger.severe("Error reading transport stream: " + e.getLocalizedMessage());
        }

        return false;
    }

    private void fillBuffer() throws IOException {
        if (extraBufferSize == 0) {
            int bytesRead = inputStream.read(inputBuffer.array());
            inputBuffer.rewind();
            if (bytesRead < TransportStream.FRAME_SIZE) {
                throw new EOFException();
            }
        } else {
            extraBufferSize -= TransportStream.FRAME_SIZE;
            if (extraBufferSize == 0) {
                resizeBuffer(TransportStream.FRAME_SIZE, inputBuffer.position());
            }
        }
    }

    /**
     * Start searching FRAME_SIZE bytes from each byte with a value of SYNC_BYTE_VALUE.
     * Each such byte we find indicates one good packet. Once we find PACKETS_UNTIL_RESYNC sequential packets,
     * return the first of them and setup our buffers to point to the rest.
     */
    private TransportStreamPacket createPacketAtNextSyncByte(long nextPacketId) throws IOException {
        TransportStreamPacket packet = null;
        int pos = 0;
        inputBuffer.rewind();
        while (packet == null) {
            if (pos == inputBuffer.capacity()) {
                resizeAndFillInputBuffer(inputBuffer.capacity() + TransportStream.FRAME_SIZE);
            }
            if (inputBuffer.get(pos) == SYNC_BYTE_VALUE) {
                int syncedPackets = 0;
                for (int i = 1; i <= PACKETS_UNTIL_RESYNC; i++) {
                    int nextSyncPos = pos + (i * TransportStream.FRAME_SIZE);
                    int neededBytes = (nextSyncPos - inputBuffer.capacity()) + 1;
                    if (neededBytes > 0) {
                        resizeAndFillInputBuffer(inputBuffer.capacity() + neededBytes);
                    }
                    if (inputBuffer.get(nextSyncPos) != 0x47) {
                        // Nope, can't resynchronize from @pos
                        break;
                    } else {
                        syncedPackets++;
                    }
                }

                if (syncedPackets == PACKETS_UNTIL_RESYNC) {
                    // Looks like we re-synchronized!
                    inputBuffer.position(pos);
                    packet = TransportStreamPacket.createFrom(inputBuffer, nextPacketId);
                    // Read the rest of the following packet into our buffer
                    resizeAndFillInputBuffer(inputBuffer.capacity() + (TransportStream.FRAME_SIZE - 1));
                    extraBufferSize = inputBuffer.capacity() - pos - TransportStream.FRAME_SIZE;
                }
            }
            pos++;
        }
        return packet;
    }

    private void resizeAndFillInputBuffer(int newSize) throws IOException {
        // Resize the buffer
        int oldSize = inputBuffer.capacity();
        int neededBytes = newSize - oldSize;
        resizeBuffer(newSize, 0);

        // And fill it to capacity
        int bytesRead = inputStream.read(inputBuffer.array(), oldSize, neededBytes);
        if (bytesRead < neededBytes) {
            throw new EOFException();
        }
    }

    private void resizeBuffer(int newSize, int sourceOffset) {
        int oldSize = inputBuffer.capacity();
        ByteBuffer newBuffer = ByteBuffer.allocate(newSize);
        System.arraycopy(inputBuffer.array(), sourceOffset, newBuffer.array(), 0, Math.min(newSize, oldSize));
        int oldPos = inputBuffer.position();
        if (oldPos <= newBuffer.capacity()) {
            newBuffer.position(oldPos);
        }
        inputBuffer = newBuffer;
    }

    private boolean processPmtPacket(TransportStreamPacket packet) {
        if (packet.isPayloadStart()) {
            // Advance past pointer field
            packet.advanceDataOffset(1);
        }

        // Advance past table_id field
        int tableId = packet.readUnsignedByteFromData();
        if (tableId != 0x02) {
            TivoDecoder.logger.severe(String.format("Unexpected Table ID for PMT: 0x%02x", tableId & 0xff));
            return false;
        }

        int pmtField = packet.readUnsignedShortFromData();
        boolean longSyntax = (pmtField & 0x8000) == 0x8000;
        if (!longSyntax) {
            TivoDecoder.logger.severe("PMT packet uses unknown syntax");
            return false;
        }
        int sectionLength = pmtField & 0x0fff;

        int programNumber = packet.readUnsignedShortFromData();
        sectionLength -= 2;
        packet.advanceDataOffset(1);
        sectionLength -= 1;
        int sectionNumber = packet.readUnsignedByteFromData();
        sectionLength--;
        int lastSectionNumber = packet.readUnsignedByteFromData();
        sectionLength--;
        int pcrPid = packet.readUnsignedShortFromData() & 0x1fff;
        sectionLength -= 2;
        int programInfoLength = packet.readUnsignedShortFromData() & 0x0fff;
        sectionLength -= 2;

        if (programInfoLength > 0) {
            TivoDecoder.logger.info(String.format("Skipping %d bytes of descriptors", programInfoLength));
            packet.advanceDataOffset(programInfoLength);
        }

        // Ignore the CRC
        sectionLength -= 4;

        while (sectionLength > 0) {
            int streamTypeId = packet.readUnsignedByteFromData();
            sectionLength--;
            TransportStream.StreamType streamType = TransportStream.StreamType.valueOf(streamTypeId);

            pmtField = packet.readUnsignedShortFromData();
            sectionLength -= 2;
            int streamPid = pmtField & 0x1fff;

            pmtField = packet.readUnsignedShortFromData();
            sectionLength -= 2;
            int esInfoLength = pmtField & 0x0fff;
            while (esInfoLength > 0) {
                // TODO Not sure if we need to do anything with these streams.
                int tag = packet.readUnsignedByteFromData();
                esInfoLength--;
                sectionLength--;
                int length = packet.readUnsignedByteFromData();
                esInfoLength--;
                sectionLength--;
                for (int i = 0; i < length; i++) {
                    int val = packet.readUnsignedByteFromData();
                    esInfoLength--;
                    sectionLength--;
                }
            }

            // Create a stream for this PID unless one already exists
            if (!streams.containsKey(streamPid)) {
                TivoDecoder.logger.info(String.format("Creating a new %s stream for PID 0x%04x", streamType, streamPid));
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

    private boolean addPacketToStream(TransportStreamPacket packet) {
        TransportStream stream = streams.get(packet.getPID());
        if (stream == null) {
            TivoDecoder.logger.warning(String.format("No TransportStream exists with PID 0x%04x, ignoring packet",
                            packet.getPID())
            );
        } else if (!stream.addPacket(packet)) {
            return false;
        }
        return true;
    }
}
