# TivoLibre
TivoLibre is a Java library for decoding TiVo files to standard MPEG files. It currently only supports TiVo Transport Stream files

TivoLibre is based on TivoDecode 0.4.4.

# Command Line Usage
You can use TivoLibre as a stand-alone command-line app. It accepts three arguments: the input file, the output file, and the MAK for the TiVo the input file was retrieved from. For example:

    java -jar tivo-libre.jar tivoFilename.TiVo outputFilename.mpg 0123456789

# API Usage
The tivo-libre.jar file exposes the TivoDecoder class. TivoDecoder requires an InputStream, an OutputStream, and String representing the MAK associated with the InputStream. Call the decode() method to start the coding process; decode() returns true on success and false on failure.

    // Assume @in and @out are Path objects and mak is a String
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

TivoLibre requires commons-codec-1.9.jar or higher. This library is included in tivo-libre.jar. If you wish to use TivoLibre in a project that already includes this library, you can use the tivo-libre-no-deps.jar file.
