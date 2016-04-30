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

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

class TransportStreamDecoder extends StreamDecoder {
    private ByteBuffer inputBuffer;
    private int extraBufferSize;
    private long bytesWritten;
    private long resumeDecryptionAtByte;
    private boolean decryptionPaused;
    private long nextResumeDecryptionByteOffset;
    private long nextMaskByteOffset;
    private boolean showDebugOutput;
    private final boolean compatibilityMode;

    private static final byte SYNC_BYTE_VALUE = 0x47;
    private static final int PACKETS_UNTIL_RESYNC = 4;
    private static final int DECRYPTION_PAUSED_INTERVAL = 0x100000;

    private final static Logger logger = LoggerFactory.getLogger(TransportStreamDecoder.class);
    
    public TransportStreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
                                  OutputStream outputStream, boolean compatibilityMode) {
        super(decoder, mpegOffset, inputStream, outputStream);
        inputBuffer = ByteBuffer.allocate(TransportStream.FRAME_SIZE);
        this.compatibilityMode = compatibilityMode;
    }

    @Override
    public boolean process() {
        try {
            advanceToMpegOffset();
            logger.debug("Starting TS processing at position {}", inputStream.getPosition());

            while (true) {
                fillBuffer();

//                if (bytesWritten > 0x39700000L) {
//                    return false;
//                }

                TransportStreamPacket packet;
                try {
                    packet = TransportStreamPacket.createFrom(inputBuffer, ++packetCounter);
                } catch (TransportStreamException e) {
                    logger.warn("{}", e.getLocalizedMessage());
                    packet = null;
                    while (packet == null) {
                        try {
                            packet = createPacketAtNextSyncByte(++packetCounter);
                        } catch (TransportStreamException e2) {
                            logger.warn("Problem with this packet, moving on to the next: ", e);
                        }
                    }
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("Re-synched at packet %d (byte 0x%x)", packetCounter, bytesWritten));
                    }
                }

                switch (packet.getPacketType()) {
                    case PROGRAM_ASSOCIATION_TABLE:
                        if (!processPatPacket(packet)) {
                            logger.error("Error processing PAT packet");
                            return false;
                        }
                        break;
                    case AUDIO_VIDEO_PRIVATE_DATA:
                        if (packet.getPID() == patData.getProgramMapPid()) {
                            packet.setIsPmt(true);
                            if (!processPmtPacket(packet)) {
                                logger.error("Error processing PMT packet");
                                return false;
                            }
                        } else {
                            TransportStream stream = streams.get(packet.getPID());
                            if (stream != null && stream.getType() == TransportStream.StreamType.PRIVATE_DATA) {
                                packet.setIsTivo(true);
                            }

                            if (packet.isTivo()) {
                                if (!processTivoPacket(packet)) {
                                    logger.error("Error processing TiVo packet");
                                    return false;
                                }
                            }
                        }
                        break;
                    case NULL:
                        // These packets are for maintaining a constant bit-rate; just copy them through to the output.
                        logger.debug("NULL packet");
                        if (!compatibilityMode) {
                            bytesWritten += packet.getBytes().length;
                            continue;
                        }
                        break;
                    default:
                        logger.warn("Unsupported packet type: {}", packet.getPacketType());
                        return false;
                }

                decryptAndWritePacket(packet);
                if (logger.isDebugEnabled() && packetCounter % 100000 == 0) {
//                if (bytesWritten > 0x39700000L - 188L) {
//                    showDebugOutput = true;
                    logger.debug(String.format("PacketId: %,d Type: %s PID: 0x%04x Position after reading: %,d",
                            packetCounter, packet.getPacketType(), packet.getPID(), inputStream.getPosition())
                    );
//                    logger.debug("{}", packet);
//                    logger.debug("Packet data:\n" + packet.dump());
                }
            }
        } catch (EOFException e) {
            logger.info("End of file reached");
            return true;
        } catch (IOException e) {
            logger.error("Error reading transport stream: ", e);
        }

        return false;
    }

    private void fillBuffer() throws IOException {
        if (extraBufferSize == 0) {
            int bytesRead = inputStream.read(inputBuffer.array());
            inputBuffer.rewind();
            if (bytesRead == -1) {
                throw new EOFException();
            } else if (bytesRead < TransportStream.FRAME_SIZE) {
                logger.warn("Only read {} bytes, expected {}", bytesRead, TransportStream.FRAME_SIZE);
                inputBuffer.limit(bytesRead);
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
        int startPos = inputBuffer.position() - TransportStream.FRAME_SIZE;
        int currentPos = startPos + 1; // Ensure we pass the start of the frame with the invalid sync byte or error flag set
        while (packet == null) {
            if (currentPos == inputBuffer.capacity()) {
                resizeAndFillInputBuffer(inputBuffer.capacity() + TransportStream.FRAME_SIZE);
            }
            if (inputBuffer.get(currentPos) == SYNC_BYTE_VALUE) {
                int syncedPackets = 0;
                for (int i = 1; i <= PACKETS_UNTIL_RESYNC; i++) {
                    int nextSyncPos = currentPos + (i * TransportStream.FRAME_SIZE);
                    int neededBytes = (nextSyncPos - inputBuffer.capacity()) + 1;
                    if (neededBytes > 0) {
                        resizeAndFillInputBuffer(inputBuffer.capacity() + neededBytes);
                    }
                    if (inputBuffer.get(nextSyncPos) != 0x47) {
                        // Nope, can't resynchronize from @currentPos
                        break;
                    } else {
                        syncedPackets++;
                    }
                }

                if (syncedPackets == PACKETS_UNTIL_RESYNC) {
                    // Looks like we re-synchronized!
                    int unsynchronizedLength = currentPos - startPos;
                    if (unsynchronizedLength > 0) {
                        long deltaToNextInterval = DECRYPTION_PAUSED_INTERVAL - (bytesWritten & 0xfffff);
                        resumeDecryptionAtByte = bytesWritten + unsynchronizedLength;
                        logger.debug(String.format("Starting value for resumeDecryptionAtByte: 0x%x", resumeDecryptionAtByte));
                        // We'll resume decryption at the next position that's evenly divisible by the TS frame size
                        while (resumeDecryptionAtByte % DECRYPTION_PAUSED_INTERVAL != 0) {
                            resumeDecryptionAtByte += TransportStream.FRAME_SIZE;
                        }
                        logger.debug(String.format("Resume decryption at: 0x%x", resumeDecryptionAtByte));
                        boolean maskThirdByte = nextResumeDecryptionByteOffset == 0;
                        nextResumeDecryptionByteOffset = bytesWritten + deltaToNextInterval;
                        outputUnsynchronizedBytes(startPos, unsynchronizedLength, maskThirdByte);
                        pauseDecryption();
                    }
                    inputBuffer.position(currentPos);
                    packet = TransportStreamPacket.createFrom(inputBuffer, nextPacketId);
                    // Read the rest of the following packet into our buffer
                    resizeAndFillInputBuffer(inputBuffer.capacity() + (TransportStream.FRAME_SIZE - 1));
                    extraBufferSize = inputBuffer.capacity() - currentPos - TransportStream.FRAME_SIZE;
                }
            }
            currentPos++;
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

    /**
     * Write out the unsynchronized data. We do this to ensure binary compliance with the TiVo DirectShow filter.
     */
    private void outputUnsynchronizedBytes(int offset, int length, boolean maskThirdByte) throws IOException {
        // The TiVo DirectShow filter includes these bytes in the output, so we will, too.

        byte[] bytes = inputBuffer.array();
        if (maskThirdByte && length >= 3) {
            // The DirectShow filter seems to do this; it's purpose is a mystery
            bytes[offset + 3] &= 0x3F;
        }
        while (nextResumeDecryptionByteOffset <= bytesWritten + length) {
            bytes[offset + (int) (nextResumeDecryptionByteOffset - bytesWritten) + 3] &= 0x3F;
            nextResumeDecryptionByteOffset += 0x100000;
        }
        if (compatibilityMode) {
            logger.debug(String.format(
                    "Writing unsynchronized bytes from %d to %d (0x%x to 0x%x)%noffset = %d, length = %d",
                    bytesWritten, bytesWritten + length, bytesWritten, bytesWritten + length, offset, length)
            );
            outputStream.write(inputBuffer.array(), offset, length);
        }
        // Pretend we wrote the extra bytes; we use this offset to determine when to resume decryption
        bytesWritten += length;
    }

    /**
     * Tell each stream to stop decrypting packets until its key changes.
     */
    private void pauseDecryption() {
        decryptionPaused = true;
        streams.forEach((id, stream) -> stream.pauseDecrypting());
    }

    private void resumeDecryption() {
        decryptionPaused = false;
        resumeDecryptionAtByte = 0;
        nextResumeDecryptionByteOffset = 0;
        nextMaskByteOffset = 0;
        streams.forEach((id, stream) -> stream.resumeDecrypting());
    }

    private boolean processPmtPacket(TransportStreamPacket packet) {
        if (packet.isPayloadStart()) {
            // Advance past pointer field
            packet.advanceDataOffset(1);
        }

        // Advance past table_id field
        int tableId = packet.readUnsignedByteFromData();
        if (tableId != 0x02) {
            logger.error(String.format("Unexpected Table ID for PMT: 0x%02x", tableId & 0xff));
            return false;
        }

        int pmtField = packet.readUnsignedShortFromData();
        boolean longSyntax = (pmtField & 0x8000) == 0x8000;
        if (!longSyntax) {
            logger.error("PMT packet uses unknown syntax");
            return false;
        }
        int sectionLength = pmtField & 0x0fff;

        int programNumber = packet.readUnsignedShortFromData();
        sectionLength -= 2;
        int versionAndNextField = packet.readUnsignedByteFromData();
        int version = versionAndNextField & 0x3e;
        boolean currentNextIndicator = (versionAndNextField & 0x01) == 0x01;
        sectionLength -= 1;
        int sectionNumber = packet.readUnsignedByteFromData();
        sectionLength--;
        int lastSectionNumber = packet.readUnsignedByteFromData();
        sectionLength--;
        int pcrPid = packet.readUnsignedShortFromData() & 0x1fff;
        sectionLength -= 2;
        int programInfoLength = packet.readUnsignedShortFromData() & 0x0fff;
        sectionLength -= 2;

        if (logger.isTraceEnabled()) {
            logger.trace(
                    String.format("Program number: 0x%04x Section number: 0x%02x Last section number: 0x%02x " + "" +
                                    "Version: 0x%02x CurrentNextIndicator: %s PCR PID: 0x%04x",
                            programNumber, sectionNumber, lastSectionNumber, version, currentNextIndicator, pcrPid));
        }
        if (programInfoLength > 0) {
            logger.trace("Skipping {} bytes of descriptors", programInfoLength);
            packet.advanceDataOffset(programInfoLength);
        }

        // Ignore the CRC at the end
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
            packet.advanceDataOffset(esInfoLength);
            sectionLength -= esInfoLength;

            // Create a stream for this PID unless one already exists
            if (!streams.containsKey(streamPid)) {
                logger.debug(String.format("Creating a new %s stream for PID 0x%04x (type=0x%02x)",
                        streamType, streamPid, streamTypeId)
                );
                TransportStream stream = new TransportStream(turingDecoder, streamType);
                streams.put(streamPid, stream);
            }
        }

        return true;
    }

    private boolean processTivoPacket(TransportStreamPacket packet) {
        int fileType = packet.readIntFromData();
        if (fileType != 0x5469566f) {
            logger.error(String.format("Invalid TiVo private data fileType: 0x%08x", fileType));
            return false;
        }

        int validator = packet.readUnsignedShortFromData();
        if (validator != 0x8103) {
            logger.error(String.format("Invalid TiVo private data validator: 0x%04x", validator));
            return false;
        }

        packet.advanceDataOffset(3);

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
                logger.error(String.format("No TransportStream with ID 0x%04x found", packetId));
                return false;
            }
//            logger.debug(String.format("Setting key for stream 0x%03x (0x%04x)", streamId, packetId));
            stream.setStreamId(streamId);
            stream.setKey(packet.readBytesFromData(16));
            streamLength -= 16;
        }

        return true;
    }

    private void decryptAndWritePacket(TransportStreamPacket packet) {
        TransportStream stream = getPacketStream(packet);

        byte[] packetBytes = stream.processPacket(packet);
//        byte[] packetBytes;
//        if (showDebugOutput) {
//            packetBytes = stream.processPacket(packet, true, 152);
//            logger.debug("Decrypted packetBytes:\n{}", TivoDecoder.bytesToHexString(packetBytes));
//        } else {
//            packetBytes = stream.processPacket(packet);
//        }

        if (decryptionPaused) {
            maskBytes(packetBytes);
        }

        writePacketBytes(packetBytes);

        if (resumeDecryptionAtByte > 0 && resumeDecryptionAtByte <= bytesWritten) {
            logger.warn(String.format("Resuming decryption at 0x%x, bytesWritten = 0x%x",
                    resumeDecryptionAtByte, bytesWritten)
            );
            resumeDecryption();
        }
    }

    private TransportStream getPacketStream(TransportStreamPacket packet) {
        TransportStream stream = streams.get(packet.getPID());
        if (stream == null) {
            logger.warn(String.format("No TransportStream exists with PID 0x%04x, creating one",
                    packet.getPID())
            );
            stream = new TransportStream(turingDecoder, TransportStream.StreamType.NOT_IN_PMT);
            streams.put(packet.getPID(), stream);
        }
        return stream;
    }

    /**
     * This is for binary compatibility with the TiVo DirectShow filter: it masks bytes at certain intervals
     * after a loss of synchronization. The exact rules TiVo uses for this masking are unknown; this is a best
     * guess based on the output of their DirectShow filter.
     */
    private void maskBytes(byte[] packetBytes) {
        if (nextResumeDecryptionByteOffset > 0 && packetBytes.length + bytesWritten > nextResumeDecryptionByteOffset + 3) {
            int offset = (int) (nextResumeDecryptionByteOffset - bytesWritten);
            int headerBits = intFromByteArray(packetBytes, offset);
            TransportStreamPacket.Header header = new TransportStreamPacket.Header(headerBits);
            if (header.isValid() && !header.isPriority()) {
                logger.debug(String.format("Found a valid TS header at 0x%x, pid=0x%04x, checking next frame", nextResumeDecryptionByteOffset, header.getPID()));
                nextMaskByteOffset = nextResumeDecryptionByteOffset + TransportStream.FRAME_SIZE;
            }
            nextResumeDecryptionByteOffset += 0x100000;
            packetBytes[offset + 3] &= 0x3F;
        }
        if (nextMaskByteOffset > 0 && packetBytes.length + bytesWritten > nextMaskByteOffset + 3) {
            logger.debug(String.format("Masking byte at 0x%x", nextMaskByteOffset));
            int offset = (int) (nextMaskByteOffset - bytesWritten);
            packetBytes[offset + 3] &= 0x3F;

            int headerBits = intFromByteArray(packetBytes, offset);
            TransportStreamPacket.Header header = new TransportStreamPacket.Header(headerBits);
            if (header.isValid()) {
                nextMaskByteOffset += TransportStream.FRAME_SIZE;
            } else {
                nextMaskByteOffset = 0;
            }
        }
    }

    int intFromByteArray(byte[] bytes, int offset) {
        return bytes[offset] << 24 | (bytes[offset + 1] & 0xFF) << 16 |
                (bytes[offset + 2] & 0xFF) << 8 | (bytes[offset + 3] & 0xFF);
    }

    private void writePacketBytes(byte[] packetBytes) {
        try {
            if (!decryptionPaused || compatibilityMode) {
                outputStream.write(packetBytes);
            }
            bytesWritten += packetBytes.length;
        } catch (Exception e) {
            logger.error("Error writing file: ", e);
            throw new RuntimeException();
        }
    }
}
