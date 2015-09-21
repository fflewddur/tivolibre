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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.apache.commons.cli.*;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.prefs.Preferences;

/**
 * Simple driver application for command line use.
 */
public class DecoderApp {
    private Options options;
    private CommandLine cli;

    private static final String PREF_MAK = "mak";

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
            TivoDecoder.logger.error("Parsing command line options failed: {}", e.getLocalizedMessage());
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

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (cli.hasOption('d')) {
            root.setLevel(Level.DEBUG);
        } else {
            root.setLevel(Level.ERROR);
        }

        DecoderOptions decoderOptions = new DecoderOptions();

        decoderOptions.mak = loadMak();
        if (!cli.hasOption('m')) {
            if (decoderOptions.mak == null) {
                System.err.format("Error: You must provide your media access key%n");
                showUsage();
                System.exit(1);
            }
        } else {
            decoderOptions.mak = cli.getOptionValue('m');
            saveMak(decoderOptions.mak);
        }

        if (cli.hasOption("compat-mode")) {
            TivoDecoder.logger.debug("Running in compatibility mode");
            decoderOptions.compatibilityMode = true;
        }

        if (cli.hasOption('D')) {
            decoderOptions.dumpMetadata = true;
        }

        if (cli.hasOption('x')) {
            decoderOptions.noVideo = true;
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
                decode(inputStream, outputStream, decoderOptions);
            } catch (FileNotFoundException e) {
                TivoDecoder.logger.error("Input file {} not found: {}", cli.getOptionValue('i'), e.getLocalizedMessage());
            } finally {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
            }
        } catch (IOException e) {
            TivoDecoder.logger.error("IOException: {}", e.getLocalizedMessage(), e);
        }
    }

    private void showUsage() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar tivo-libre.jar -i input.TiVo -o output.mpg -m 0123456789", options);
    }

    private void decode(InputStream input, OutputStream output, DecoderOptions options) {
        try (BufferedInputStream inputStream = new BufferedInputStream(input);
             BufferedOutputStream outputStream = new BufferedOutputStream(output)) {
            TivoDecoder decoder = new TivoDecoder.Builder()
                    .input(inputStream).output(outputStream)
                    .mak(options.mak).compatibilityMode(options.compatibilityMode)
                    .build();

            if (output != System.out) {
                System.out.println(TivoDecoder.QUALCOMM_MSG);
            }

            boolean decodeSuccessful;
            if (options.noVideo) {
                decodeSuccessful = decoder.decodeMetadata();
            } else {
                decodeSuccessful = decoder.decode();
            }

            if (decodeSuccessful && options.dumpMetadata) {
                dumpMetadata(decoder);
            }

        } catch (FileNotFoundException e) {
            TivoDecoder.logger.error("Error: {}", e.getLocalizedMessage());
        } catch (IOException e) {
            TivoDecoder.logger.error("Error reading/writing files: {}", e.getLocalizedMessage());
        }
    }

    private Options buildCliOptions() {
        Options options = new Options();

        options.addOption("D", "metadata", false, "Dump TiVo recording metadata to XML files");
        options.addOption("d", "debug", false, "Show debugging information while decoding");
        options.addOption("h", "help", false, "Show this help message and exit");
        options.addOption("v", "version", false, "Show version and exit");
        options.addOption("x", "no-video", false, "Exit after processing metadata; doesn't decode the video");
        Option option = Option.builder().longOpt("compat-mode").desc("Don't fix problems in the TiVo file; produces output that " +
                "is binary compatible with the TiVo DirectShow filter").build();
        options.addOption(option);
        option = Option.builder("o").argName("FILENAME").longOpt("output").hasArg().
                desc("Output file (defaults to standard output)").build();
        options.addOption(option);
        option = Option.builder("i").argName("FILENAME").longOpt("input").hasArg().
                desc("File to decode (defaults to standard input)").build();
        options.addOption(option);
        option = Option.builder("m").argName("MAK").longOpt("mak").hasArg().
                desc("Your media access key (will be saved between program executions)").build();
        options.addOption(option);

        return options;
    }

    private void dumpMetadata(TivoDecoder decoder) {
        int counter = 0;
        for (Document d : decoder.getMetadata()) {
            String chunkFilename = String.format("chunk-%02d.xml", counter++);
            TivoDecoder.logger.debug("Saving metadata chunk {} to {}...", counter, chunkFilename);
            try {
                OutputStream out = new FileOutputStream(chunkFilename);
                printDocument(d, out);
            } catch (IOException | TransformerException e) {
                TivoDecoder.logger.error("Error saving file {}: ", chunkFilename, e);
            }
        }
    }

    // From http://stackoverflow.com/questions/2325388/java-shortest-way-to-pretty-print-to-stdout-a-org-w3c-dom-document
    private static void printDocument(Document doc, OutputStream out) throws IOException, TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        transformer.transform(new DOMSource(doc),
                new StreamResult(new OutputStreamWriter(out, "UTF-8")));
    }

    private void saveMak(String mak) {
        Preferences prefs = getPrefs();
        prefs.put(PREF_MAK, mak);
    }

    private String loadMak() {
        Preferences prefs = getPrefs();
        return prefs.get(PREF_MAK, null);
    }

    private Preferences getPrefs() {
        return Preferences.userNodeForPackage(DecoderApp.class);
    }

    private static class DecoderOptions {
        String mak;
        boolean compatibilityMode;
        boolean noVideo;
        boolean dumpMetadata;
    }
}
