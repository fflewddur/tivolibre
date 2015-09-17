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

import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Read an InputStream into a ByteBuffer and provide methods for extracting primitive data types in big-endian order.
 * This class allows us to use a PipedInputStream as our input source without worrying about overflowing the pipe's buffer.
 * If we're not using a PipedInputStream, pass everything through to a traditional DataInputStream.
 */
class CountingDataInputStream implements AutoCloseable {
    private final boolean piped;
    private DataInputStream inputStream;
    private ConcurrentByteBuffer byteBuffer; // This hold the unread portion of @stream
    private long position; // Current read position in @stream, in bytes.
    private Thread readerThread;

    public CountingDataInputStream(InputStream stream) {
        if (stream instanceof PipedInputStream) {
            piped = true;
            byteBuffer = new ConcurrentByteBuffer();
            StreamReader streamReader = new StreamReader(stream, byteBuffer);
            readerThread = new Thread(streamReader);
            readerThread.start();
        } else {
            piped = false;
            inputStream = new DataInputStream(stream);
        }
    }

    public long getPosition() {
        return position;
    }

    public int read(byte[] buffer) throws IOException {
        return read(buffer, 0, buffer.length);
    }

    public int read(byte[] buffer, int offset, int length) throws IOException {
        int totalBytesRead = 0;
        if (piped) {
            totalBytesRead = byteBuffer.read(buffer, offset, length);
        } else {
            while (length > 0) {
                int bytesRead = inputStream.read(buffer, offset, length);
                if (bytesRead > 0) {
                    totalBytesRead += bytesRead;
                    length -= bytesRead;
                    offset += bytesRead;
                } else {
                    break;
                }
            }
        }
        if (totalBytesRead == 0) {
            throw new EOFException();
        }
        position += totalBytesRead;
        return totalBytesRead;
    }

    public byte readByte() throws IOException {
        byte val;
        if (piped) {
            val = byteBuffer.readByte();
        } else {
            val = inputStream.readByte();
        }
        position += Byte.BYTES;
        return val;
    }

    public int readInt() throws IOException {
        int val;
        if (piped) {
            val = byteBuffer.readInt();
        } else {
            val = inputStream.readInt();
        }
        position += Integer.BYTES;
        return val;
    }

    public int readUnsignedByte() throws IOException {
        int val;
        if (piped) {
            val = byteBuffer.readUnsignedByte();
        } else {
            val = inputStream.readUnsignedByte();
        }
        position += Byte.BYTES;
        return val;
    }

    public int readUnsignedShort() throws IOException {
        int val;
        if (piped) {
            val = byteBuffer.readUnsignedShort();
        } else {
            val = inputStream.readUnsignedShort();
        }
        position += Short.BYTES;
        return val;
    }

    public int skipBytes(int bytesToSkip) throws IOException {
        int totalBytesSkipped = 0;
        if (piped) {
            totalBytesSkipped = byteBuffer.skipBytes(bytesToSkip);
        } else {
            while (bytesToSkip > 0) {
                int bytesSkipped = inputStream.skipBytes(bytesToSkip);
                if (bytesSkipped > 0) {
                    totalBytesSkipped += bytesSkipped;
                    bytesToSkip -= bytesSkipped;
                } else {
                    break;
                }
            }
        }
        if (totalBytesSkipped < bytesToSkip) {
            TivoDecoder.logger.error("Could only skip {} of {} requested bytes", totalBytesSkipped, bytesToSkip);
        }
        position += totalBytesSkipped;
        return totalBytesSkipped;
    }

    @Override
    public void close() throws IOException {
        TivoDecoder.logger.debug("Closing CountingDataInputStream. Final read position: " + position);
        if (piped) {
            if (readerThread.isAlive()) {
                readerThread.interrupt();
            }
        } else {
            inputStream.close();
        }
    }

    /**
     * Slurp up all the data from @source as quickly as it can.
     */
    private static class StreamReader implements Runnable {
        private InputStream source;
        private ConcurrentByteBuffer destination;

        public StreamReader(InputStream stream, ConcurrentByteBuffer destination) {
            this.source = stream;
            this.destination = destination;
        }

        @Override
        public void run() {
            try {
                boolean moreData = true;
                while (moreData) {
                    if (Thread.interrupted()) {
                        TivoDecoder.logger.warn("StreamReader thread interrupted");
                        return;
                    }
                    moreData = destination.readFrom(source);
                }
                TivoDecoder.logger.info("End of file reached");
            } catch (IOException e) {
                TivoDecoder.logger.error("IOException reading file: ", e);
            }
        }
    }

    /**
     * Wrapper for ByteBuffer that allows one thread to read from the buffer while a separate thread writes to it.
     * Data is never removed or overwritten before it has been read from the buffer.
     */
    private static class ConcurrentByteBuffer {
        private ByteBuffer buffer;
        private byte[] bufferArray;
        private int writePos; // The index in buffer to write the next byte to
        private int readPos; // The index in buffer to read the next byte from
        private Lock readLock;
        private Lock writeLock;
        private boolean sourceClosed;
        private int readDelay;

        private static final int INITIAL_BUFFER_SIZE = 1024 * 1024 * 16; // 16MB
        private static final int BUFFER_EXPAND_FACTOR = 2;
        private static final float SHIFT_RATIO = 0.9f; // when readPos passes SHIFT_RATIO * buffer.length, shift buffer back to 0
        private static final int MAX_READ_SIZE = 1024 * 64; // 64KB
        private static final int INITIAL_READ_DELAY = 1; // milliseconds to sleep after each network read

        public ConcurrentByteBuffer() {
            bufferArray = new byte[INITIAL_BUFFER_SIZE];
            buffer = ByteBuffer.wrap(bufferArray);
            ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
            readLock = lock.readLock();
            writeLock = lock.writeLock();
            readDelay = INITIAL_READ_DELAY;
        }

        public boolean readFrom(InputStream stream) throws IOException {
            writeLock.lock();
            if (expandBufferIfNeeded()) {
                int offset = writePos;
                // Limit the number of bytes read to ensure we don't hold the writeLock for too long
                int bytesRead = stream.read(bufferArray, offset, Math.min(bufferArray.length - offset, MAX_READ_SIZE));
                TivoDecoder.logger.trace("Read {} bytes from input stream", bytesRead);
                if (bytesRead == -1) {
                    sourceClosed = true;
                    writeLock.unlock();
                    return false;
                }
                writePos += bytesRead;
            }
            shiftBufferIfNeeded();
            writeLock.unlock();
            try {
                Thread.sleep(readDelay);
            } catch (InterruptedException e) {
                TivoDecoder.logger.error("Stream reading thread interrupted: ", e);
            }
            return true;
        }

        /**
         * Expand our buffer and shift its contents such that readPos = 0.
         * Double the read delay, since this buffer filling up means we're reading too quickly.
         * Return false if the buffer can not be expanded.
         */
        private boolean expandBufferIfNeeded() {
            int spaceRemaining = bufferArray.length - writePos;
            if (spaceRemaining == 0) {
                int newBufferSize = bufferArray.length * BUFFER_EXPAND_FACTOR;
                if (newBufferSize < 0) {
                    return false;
                }
                if (TivoDecoder.logger.isDebugEnabled()) {
                    TivoDecoder.logger.debug(String.format("Expanding buffer from %,d MB to %,d MB (readPos=%d, writePos=%d)",
                            bufferArray.length / (1024 * 1024), newBufferSize / (1024 * 1024), readPos, writePos));
                }
                resizeBuffer(newBufferSize);
                readDelay *= 2;
                TivoDecoder.logger.debug("Increasing readDelay to {} ms", readDelay);
            }
            return true;
        }

        private void shiftBufferIfNeeded() {
            if (readPos > bufferArray.length * SHIFT_RATIO) {
                int newBufferSize = Math.max((writePos - readPos) * BUFFER_EXPAND_FACTOR, INITIAL_BUFFER_SIZE);
                if (TivoDecoder.logger.isDebugEnabled()) {
                    TivoDecoder.logger.debug(String.format("Shifting buffer from %,d to %,d (resize to %d MB)",
                            readPos, 0, newBufferSize / (1024 * 1024)));
                }
                resizeBuffer(newBufferSize);
            }
        }

        private void resizeBuffer(int newBufferSize) {
            if (bufferArray.length == newBufferSize) {
                System.arraycopy(bufferArray, readPos, bufferArray, 0, writePos - readPos);
            } else {
                byte[] newBuffer = new byte[newBufferSize];
                System.arraycopy(bufferArray, readPos, newBuffer, 0, writePos - readPos);
                bufferArray = newBuffer;
                buffer = ByteBuffer.wrap(bufferArray);
                System.gc();
            }
            writePos -= readPos;
            readPos = 0;
        }

        public int read(byte[] destination, int offset, int length) throws IOException {
            boolean completed = false;
            int val = 0;
            while (!completed) {
                readLock.lock();
                if (readPos + length <= writePos) {
                    System.arraycopy(buffer.array(), readPos, destination, offset, length);
                    val = length;
                    readPos += val;
                    completed = true;
                } else if (sourceClosed) {
                    readLock.unlock();
                    throw new EOFException();
                } else {
                    TivoDecoder.logger.trace("Waiting for {} more bytes (readPos={}, writePos={})",
                            (readPos + length) - writePos, readPos, writePos);
                }
                readLock.unlock();
            }
            return val;
        }

        public byte readByte() throws IOException {
            boolean completed = false;
            byte val = 0;
            while (!completed) {
                readLock.lock();
                if (readPos + Byte.BYTES <= writePos) {
                    val = buffer.get(readPos);
                    readPos += Byte.BYTES;
                    completed = true;
                } else if (sourceClosed) {
                    readLock.unlock();
                    throw new EOFException();
                } else {
                    TivoDecoder.logger.trace("Waiting for {} more bytes (readPos={}, writePos={})",
                            (readPos + Byte.BYTES) - writePos, readPos, writePos);
                }
                readLock.unlock();
            }
            return val;
        }

        public int readInt() throws IOException {
            boolean completed = false;
            int val = 0;
            while (!completed) {
                readLock.lock();
                if (readPos + Integer.BYTES <= writePos) {
                    val = buffer.getInt(readPos);
                    readPos += Integer.BYTES;
                    completed = true;
                } else if (sourceClosed) {
                    readLock.unlock();
                    throw new EOFException();
                } else {
                    TivoDecoder.logger.trace("Waiting for {} more bytes (readPos={}, writePos={})",
                            (readPos + Integer.BYTES) - writePos, readPos, writePos);
                }
                readLock.unlock();
            }
            return val;
        }

        public int readUnsignedByte() throws IOException {
            boolean completed = false;
            int val = 0;
            while (!completed) {
                readLock.lock();
                if (readPos + Byte.BYTES <= writePos) {
                    val = buffer.get(readPos) & 0xff;
                    readPos += Byte.BYTES;
                    completed = true;
                } else if (sourceClosed) {
                    readLock.unlock();
                    throw new EOFException();
                } else {
                    TivoDecoder.logger.trace("Waiting for {} more bytes (readPos={}, writePos={})",
                            (readPos + Byte.BYTES) - writePos, readPos, writePos);
                }
                readLock.unlock();
            }
            return val;
        }

        public int readUnsignedShort() throws IOException {
            boolean completed = false;
            int val = 0;
            while (!completed) {
                readLock.lock();
                if (readPos + Short.BYTES <= writePos) {
                    val = buffer.getShort(readPos) & 0xffff;
                    readPos += Short.BYTES;
                    completed = true;
                } else if (sourceClosed) {
                    readLock.unlock();
                    throw new EOFException();
                } else {
                    TivoDecoder.logger.trace("Waiting for {} more bytes (readPos={}, writePos={})",
                            (readPos + Short.BYTES) - writePos, readPos, writePos);
                }
                readLock.unlock();
            }
            return val;
        }

        public int skipBytes(int bytesToSkip) throws IOException {
            boolean completed = false;
            while (!completed) {
                readLock.lock();
                // Can we skip the desired number of bytes without reading more from the input stream?
                if (readPos + bytesToSkip <= writePos) {
                    readPos += bytesToSkip;
                    completed = true;
                } else if (sourceClosed) {
                    readLock.unlock();
                    throw new EOFException();
                } else {
                    TivoDecoder.logger.trace("Waiting for {} more bytes (readPos={}, writePos={})",
                            (readPos + bytesToSkip) - writePos, readPos, writePos);
                }
                readLock.unlock();
            }
            return bytesToSkip;
        }
    }
}
