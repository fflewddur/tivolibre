# TivoLibre
TivoLibre is a Java library for decoding TiVo files to standard MPEG files. It supports both TiVo Transport Stream and TiVo Program Stream files.

# Downloading
The latest release can always be found at https://github.com/fflewddur/tivolibre/releases.

TivoLibre comes in two flavors: a runnable JAR (TivoDecoder.jar) and a lightweight library (tivo-libre.jar). If you plan to run TivoLibre directly from the command line, use TivoDecoder.jar. If you plan to embed TivoLibre in your own software project, we recommend using tivo-libre.jar.

# Command Line Usage
You can use TivoLibre as a stand-alone command-line app. By default, it will read from standard input and write to standard output. You can specify input and output files with the -i and -o command-line parameters, respectively. You must specify the media access key (MAK) for decoding the provided input file with the -m parameter. For example:

    java -jar TivoDecoder.jar -i input.TiVo -o output.mpg -m 0123456789

Your media access key will be saved between program executions, so you only need to specify it the first time you run TivoLibre.

To view the full list of options, use the -h command-line parameter:

    java -jar TivoDecoder.jar -h

# API Usage
The tivo-libre.jar file exposes the TivoDecoder class. Use the provided Builder to create new TivoDecoder instances. Building a TivoDecoder requires an InputStream, an OutputStream, and a String representing the MAK associated with the InputStream; additional parameters are optional. Call the `decode()` method to start the coding process; `decode()` is a blocking method that returns `true` on success and `false` on failure.

    // Assume @in and @out are Path objects and @mak is a String
    try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(in.toFile()));
        BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(out.toFile()))) {
        TivoDecoder decoder = new TivoDecoder.Builder().input(inputStream).output(outputStream).mak(mak).build();
        decoder.decode();
    } catch (FileNotFoundException e) {
        TivoDecoder.logger.error("Error: {}", e.getLocalizedMessage());
    } catch (IOException e) {
        TivoDecoder.logger.error("Error reading/writing files: {}", e.getLocalizedMessage());
    }

The TivoDecoder class also includes methods for fetching the metadata embedded in a TiVo file (`List<Document> getMetadata()`) and for processing a file's metadata without also decoding its audio and video streams (`boolean decodeMetadata()`).

TivoLibre can be configured to use your app's existing logging framework via the SLF4J logging facade.

# Building
TivoLibre uses the Gradle build system and expects a source .TiVo file to be available for running tests. You can specify the location of and MAK associated with this test file by creating a gradle.properties file and adjusting the following properties to point at your preferred test file:

    # Specify settings for our unit tests
    mak = 0123456789
    testFile = test-files\\test.TiVo
    outFile = test-files\\test.mpg

You can then build the project with the command `gradle build`.

# Dependencies
TivoLibre makes use of the Stream APIs introduced in Java 8 and will not run on older Java virtual machines.

When used as a stand-alone application, TivoLibre requires commons-codec-1.9.jar and commons-cli-1.3.1.jar (or higher) from Apache Commons, as well as SLF4J and Logback. These libraries are already included in TivoDecoder.jar.

When used as a library, TivoLibre only requires commons-codec-1.9.jar (or higher) and slf4j-api.jar. If you wish to view log output from TivoLibre, you'll also need the appropriate SLF4J bindings for your preferred logging framework.

Gradle and Maven users can include these dependencies automatically by including TivoLibre from the Maven Central Repository, where it can be found with the ID *net.straylightlabs.tivo-libre*.

# Known Issues
A list of known problems is available at https://github.com/fflewddur/tivolibre/issues. You can help us improve TivoLibre by reporting any problems you encounter.

# Acknowledgements

TivoLibre is based on TivoDecode 0.4.4 and uses the Turing encryption algorithm, which was developed by QUALCOMM. Testing TivoLibre has been greatly helped by members of the TiVo Community Forum (http://www.tivocommunity.com/), especially forum member *moyekj*.