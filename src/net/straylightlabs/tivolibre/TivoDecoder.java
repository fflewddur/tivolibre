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
    public final static String VERSION = "0.5.0";

    public TivoDecoder(InputStream inputStream, OutputStream outputStream, String mak) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.mak = mak;
        logger = Logger.getLogger(TivoDecoder.class.getName());
        logger.setLevel(Level.SEVERE);
    }

    public boolean decode() {
        logger.info(QUALCOMM_MSG);
        TivoStream stream = new TivoStream(inputStream, outputStream, mak);
        return stream.process();
    }

    public static void setLogger(Logger logger) {
        TivoDecoder.logger = logger;
    }

    /**
     * Simple driver application for command line use.
     *
     * @param args Paths to the input and output files and a string representing the MAK, in that order
     */
    public static void main(String[] args) {
        System.out.format("TivoLibre %s%n", VERSION);

        if (args.length != 3) {
            System.out.format("%nTivoLibre requires three arguments: inputFile, outputFile, and MAK, in that order.%n");
            System.out.format("For example: java -jar tivo-libre.jar tivoFilename.TiVo outputFilename.mpg 0123456789%n");
            return;
        }

        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        String mak = args[2];
        try (FileInputStream inputStream = new FileInputStream(in.toFile());
             BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(out.toFile()))) {
            TivoDecoder decoder = new TivoDecoder(inputStream, outputStream, mak);
            System.out.println(QUALCOMM_MSG);
            decoder.decode();
        } catch (FileNotFoundException e) {
            TivoDecoder.logger.severe(String.format("Error: %s", e.getLocalizedMessage()));
        } catch (IOException e) {
            TivoDecoder.logger.severe(String.format("Error reading/writing files: %s", e.getLocalizedMessage()));
        }
    }
}
