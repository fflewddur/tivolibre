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

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Read an InputStream into a ByteBuffer and provide methods for extracting primitive data types in big-endian order.
 * Also keep track of our current position in the InputStream.
 */
class CountingDataInputStream implements AutoCloseable {
    private DataInputStream inputStream;
    private long position; // Current read position in @stream, in bytes.

    public CountingDataInputStream(InputStream stream) {
        inputStream = new DataInputStream(stream);
    }

    public long getPosition() {
        return position;
    }

    public int read(byte[] buffer) throws IOException {
        return read(buffer, 0, buffer.length);
    }

    public int read(byte[] buffer, int offset, int length) throws IOException {
        int totalBytesRead = 0;
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
        if (totalBytesRead == 0) {
            throw new EOFException();
        }
        position += totalBytesRead;
        return totalBytesRead;
    }

    public byte readByte() throws IOException {
        byte val = inputStream.readByte();
        position += Byte.BYTES;
        return val;
    }

    public int readInt() throws IOException {
        int val = inputStream.readInt();
        position += Integer.BYTES;
        return val;
    }

    public int readUnsignedByte() throws IOException {
        int val = inputStream.readUnsignedByte();
        position += Byte.BYTES;
        return val;
    }

    public int readUnsignedShort() throws IOException {
        int val = inputStream.readUnsignedShort();
        position += Short.BYTES;
        return val;
    }

    public int skipBytes(int bytesToSkip) throws IOException {
        int totalBytesSkipped = 0;
        while (bytesToSkip > 0) {
            int bytesSkipped = inputStream.skipBytes(bytesToSkip);
            if (bytesSkipped > 0) {
                totalBytesSkipped += bytesSkipped;
                bytesToSkip -= bytesSkipped;
            } else {
                break;
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
        inputStream.close();
    }
}
