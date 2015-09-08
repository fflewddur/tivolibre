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

import org.apache.commons.cli.*;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decodes a TiVo file to a standard MPEG file.
 * Currently only supports TiVo Transport Stream files.
 */
public class TivoDecoder {
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final String mak;

    public static Logger logger;

    public final static String QUALCOMM_MSG = "Encryption by QUALCOMM";
    public final static String VERSION = "0.5.2";

    static {
        logger = Logger.getLogger(TivoDecoder.class.getName());
        logger.setLevel(Level.SEVERE);
    }

    public TivoDecoder(InputStream inputStream, OutputStream outputStream, String mak) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.mak = mak;
    }

    public boolean decode() {
        logger.info(QUALCOMM_MSG);
        TivoStream stream = new TivoStream(inputStream, outputStream, mak);
        return stream.process();
    }

    public static void setLogger(Logger logger) {
        TivoDecoder.logger = logger;
    }

    public static void setLoggerLevel(Level level) {
        logger.setLevel(level);
    }
}
