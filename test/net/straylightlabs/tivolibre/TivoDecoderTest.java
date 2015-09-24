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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Test class for TivoDecoder.
 *
 * Expects two JVM properties:
 * - testFile=pathToTiVoFile
 * - mak=makForTestFile
 */
public class TivoDecoderTest {
    private InputStream inputStream;
    private String mak;

    @Before
    public void before() {
        String filename = System.getProperty("testFile");
        assertNotNull(filename);
        try {
            inputStream = Files.newInputStream(Paths.get(filename), StandardOpenOption.READ);
        } catch (IOException e) {
            TivoDecoder.logger.error("IOException opening inputStream '{}': ", filename, e);
        }
        assertNotNull(inputStream);

        mak = System.getProperty("mak");
        assertNotNull(mak);
    }

    @After
    public void after() {
        try {
            inputStream.close();
        } catch (IOException e) {
            TivoDecoder.logger.error("IOException closing inputStream: ", e);
        }
    }

    @Test
    public void testDecoderCreationStdInStdOut() {
        TivoDecoder decoder = new TivoDecoder.Builder().input(inputStream).output(System.out).mak(mak).build();
        assertNotNull(decoder);
    }

    @Test
    public void testMetadataDecoderCreation() {
        TivoDecoder decoder = new TivoDecoder.Builder().input(inputStream).mak(mak).build();
        assertNotNull(decoder);
    }

    @Test(expected = IllegalStateException.class)
    public void testDecoderCreationWithoutInput() {
        TivoDecoder decoder = new TivoDecoder.Builder().output(System.out).mak(mak).build();
        assertNotNull(decoder);
    }

    @Test(expected = IllegalStateException.class)
    public void testDecoderCreationWithoutMak() {
        TivoDecoder decoder = new TivoDecoder.Builder().input(inputStream).output(System.out).build();
        assertNotNull(decoder);
    }

    @Test
    public void testMetadataDecoderProcessing() {
        TivoDecoder decoder = new TivoDecoder.Builder().input(inputStream).mak(mak).build();
        assertNotNull(decoder);
        assertTrue(decoder.decodeMetadata());
    }

    @Test(expected = IllegalStateException.class)
    public void testDecoderProcessingWithoutOutput() {
        TivoDecoder decoder = new TivoDecoder.Builder().input(inputStream).mak(mak).build();
        assertNotNull(decoder);
        assertTrue(decoder.decode());
    }
}
