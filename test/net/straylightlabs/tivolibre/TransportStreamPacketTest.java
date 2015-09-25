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

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class TransportStreamPacketTest {

    @Test
    public void testValidHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(TransportStream.FRAME_SIZE);
        buffer.putInt(0x470E6FD7);
        buffer.rewind();
        TransportStreamPacket packet = TransportStreamPacket.createFrom(buffer, 0);
        TransportStreamPacket.Header header = packet.getHeader();
        assertNotNull("Header is not null", header);
        assertTrue("Header has sync bit", header.isValid());
    }

    @Test(expected = TransportStreamException.class)
    public void testUnsynchedHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(TransportStream.FRAME_SIZE);
        buffer.putInt(0x480E6FD7);
        buffer.rewind();
        TransportStreamPacket packet = TransportStreamPacket.createFrom(buffer, 0);
        TransportStreamPacket.Header header = packet.getHeader();
        assertNotNull("Header is not null", header);
        assertFalse("Header has no sync bit", header.isValid());
    }

    @Test
    public void testScrambledHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(TransportStream.FRAME_SIZE);
        buffer.putInt(0x470E6FD7);
        buffer.rewind();
        TransportStreamPacket packet = TransportStreamPacket.createFrom(buffer, 0);
        assertTrue("Packet is scrambled", packet.isScrambled());
    }

    @Test
    public void testPlainHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(TransportStream.FRAME_SIZE);
        buffer.putInt(0x470E6F17);
        buffer.rewind();
        TransportStreamPacket packet = TransportStreamPacket.createFrom(buffer, 0);
        assertFalse("Packet is not scrambled", packet.isScrambled());
    }
}
