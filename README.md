# TivoLibre
TivoLibre is a Java library for decoding TiVo files to standard MPEG files. It currently only supports TiVo Transport Stream files.

TivoLibre is based on TivoDecode 0.4.4.

# Command Line Usage
You can use TivoLibre as a stand-alone command-line app. By default, it will read from standard input and write to standard output. You can specify input and output files with the -i and -o command-line parameters, respectively. You must specify the media access key (MAK) for decoding the provided input file with the -m parameter. For example:

    java -jar tivo-libre.jar -i input.TiVo -o output.mpg -m 0123456789

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

# Dependencies
TivoLibre makes use of the Stream APIs introduced in Java 8 and will not run on older Java virtual machines.

TivoLibre requires commons-codec-1.9.jar and commons-cli-1.3.1.jar (or higher) from Apache Commons. These libraries are included in tivo-libre.jar. If you want to use TivoLibre in a project that already includes these libraries, you can use the tivo-libre-no-deps.jar file. The common-cli JAR is only needed by the DecoderApp command-line application.
