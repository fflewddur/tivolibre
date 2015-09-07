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
import java.util.logging.Level;

/**
 * Simple driver application for command line use.
 */
public class DecoderApp {
    private Options options;
    private CommandLine cli;

    public static void main(String args[]) {
        DecoderApp app = new DecoderApp();
        if (app.parseCommandLineArgs(args)) {
            app.run();
        }
    }

    public boolean parseCommandLineArgs(String[] args) {
        try {
            options = buildCliOptions();
            CommandLineParser parser = new DefaultParser();
            cli = parser.parse(options, args);
        } catch (ParseException e) {
            TivoDecoder.logger.severe("Parsing command line options failed: " + e.getLocalizedMessage());
            showUsage();
            return false;
        }

        return true;
    }

    public void run() {
        if (options == null || cli == null) {
            throw new IllegalStateException("Must call parseCommandLineArgs() before calling run()");
        }

        if (cli.hasOption('v')) {
            System.out.format("TivoLibre %s%n", TivoDecoder.VERSION);
            System.exit(0);
        } else if (cli.hasOption('h')) {
            showUsage();
            System.exit(0);
        }

        if (cli.hasOption('d')) {
            TivoDecoder.setLoggerLevel(Level.INFO);
        }

        if (!cli.hasOption('m')) {
            System.err.format("Error: You must provide your media access key%n");
            showUsage();
            System.exit(1);
        }

        try {
            InputStream inputStream = null;
            OutputStream outputStream = null;
            try {
                if (cli.hasOption('i')) {
                    inputStream = new FileInputStream(cli.getOptionValue('i'));
                } else {
                    inputStream = System.in;
                }
                if (cli.hasOption('o')) {
                    outputStream = new FileOutputStream(cli.getOptionValue('o'));
                } else {
                    outputStream = System.out;
                }
                decode(inputStream, outputStream, cli.getOptionValue('m'));
            } catch (FileNotFoundException e) {
                TivoDecoder.logger.severe(String.format("Input file %s not found: %s", cli.getOptionValue('i'),
                                e.getLocalizedMessage())
                );
            } finally {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
            }
        } catch (IOException e) {
            TivoDecoder.logger.severe("IOException: " + e.getLocalizedMessage());
        }
    }

    private void showUsage() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar tivo-libre.jar -i input.TiVo -o output.mpg -m 0123456789", options);
    }

    private void decode(InputStream input, OutputStream output, String mak) {
        try (BufferedInputStream inputStream = new BufferedInputStream(input);
             BufferedOutputStream outputStream = new BufferedOutputStream(output)) {
            TivoDecoder decoder = new TivoDecoder(inputStream, outputStream, mak);
            System.out.println(TivoDecoder.QUALCOMM_MSG);
            decoder.decode();
        } catch (FileNotFoundException e) {
            TivoDecoder.logger.severe(String.format("Error: %s", e.getLocalizedMessage()));
        } catch (IOException e) {
            TivoDecoder.logger.severe(String.format("Error reading/writing files: %s", e.getLocalizedMessage()));
        }
    }

    private Options buildCliOptions() {
        Options options = new Options();

        options.addOption("d", "debug", false, "Show debugging information");
        options.addOption("h", "help", false, "Show this help message and exit");
        options.addOption("v", "version", false, "Show version and exit");
        Option option = Option.builder("o").argName("FILENAME").longOpt("output").hasArg().
                desc("Output file (defaults to standard output)").build();
        options.addOption(option);
        option = Option.builder("i").argName("FILENAME").longOpt("input").hasArg().
                desc("File to decode (defaults to standard input)").build();
        options.addOption(option);
        option = Option.builder("m").argName("MAK").longOpt("mak").hasArg().
                desc("Your media access key (REQUIRED)").build();
        options.addOption(option);

        return options;
    }
}
