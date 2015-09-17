# TivoLibre
TivoLibre is a Java library for decoding TiVo files to standard MPEG files. It supports both TiVo Transport Stream and TiVo Program Stream files.

TivoLibre is based on TivoDecode 0.4.4.

# Downloading
The latest release can always be found at https://github.com/fflewddur/tivolibre/releases.

# Command Line Usage
You can use TivoLibre as a stand-alone command-line app. By default, it will read from standard input and write to standard output. You can specify input and output files with the -i and -o command-line parameters, respectively. You must specify the media access key (MAK) for decoding the provided input file with the -m parameter. For example:

    java -jar tivo-libre.jar -i input.TiVo -o output.mpg -m 0123456789

To view the full list of options, use the -h command-line parameter:

    java -jar tivo-libre.jar -h

# API Usage
The tivo-libre.jar file exposes the TivoDecoder class. TivoDecoder requires an InputStream, an OutputStream, and a String representing the MAK associated with the InputStream. Call the decode() method to start the coding process; decode() is a blocking method that returns true on success and false on failure.

    // Assume @in and @out are Path objects and @mak is a String
    try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(in.toFile()));
        BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(out.toFile()))) {
        TivoDecoder decoder = new TivoDecoder(inputStream, outputStream, mak);
        decoder.decode();
    } catch (FileNotFoundException e) {
        TivoDecoder.logger.severe(String.format("Error: %s", e.getLocalizedMessage()));
    } catch (IOException e) {
        TivoDecoder.logger.severe(String.format("Error reading/writing files: %s", e.getLocalizedMessage()));
    }

TivoLibre can be configured to use your app's existing logging framework via the SLF4J logging facade.

# Dependencies
TivoLibre makes use of the Stream APIs introduced in Java 8 and will not run on older Java virtual machines.

When used as a stand-alone application, TivoLibre requires commons-codec-1.9.jar and commons-cli-1.3.1.jar (or higher) from Apache Commons, as well as SLF4J and Logback. These libraries are already included in tivo-libre.jar.

When used as a library, TivoLibre only requires commons-codec-1.9.jar (or higher) and slf4j-api.jar. If you wish to view log output from TivoLibre, you'll also need the appropriate SLF4J bindings for your preferred logging framework.

# Known Issues
A list of known problems is available at https://github.com/fflewddur/tivolibre/issues. You can help us improve TivoLibre by reporting any problems you encounter.