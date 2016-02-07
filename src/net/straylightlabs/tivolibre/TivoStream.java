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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

class TivoStream {
    private TivoStreamHeader header;
    private TivoStreamChunk[] chunks;
    private TuringDecoder decoder;
    private TuringDecoder metaDecoder;
    private long metaPosition;

    private final String mak;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private boolean processVideo;
    private boolean compatibilityMode;

    private final static Logger logger = LoggerFactory.getLogger(TivoStream.class);

    public TivoStream(InputStream inputStream, OutputStream outputStream, String mak) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.mak = mak;
        this.processVideo = true;
        this.compatibilityMode = false;
    }

    public void setProcessVideo(boolean val) {
        processVideo = val;
    }

    public void setCompatibilityMode(boolean val) {
        compatibilityMode = val;
    }

    /**
     * Process @inputStream and try to decrypt it, printing the results to @outputStream.
     */
    public boolean process() {
        try (CountingDataInputStream dataInputStream = new CountingDataInputStream(inputStream)) {
            if (!processMetadata(dataInputStream)) {
                return false;
            }
            if (processVideo) {
                if (!processVideo(dataInputStream)) {
                    return false;
                }
            }
        } catch (IOException e) {
            logger.error("Error reading TiVoStream file: ", e);
            return false;
        }

        return true;
    }

    private boolean processMetadata(CountingDataInputStream dataInputStream) {
        header = new TivoStreamHeader(dataInputStream);
        if (!header.read()) {
            return false;
        }
        logger.debug("Header: " + header);

        chunks = new TivoStreamChunk[header.getNumChunks()];
        for (int i = 0; i < header.getNumChunks(); i++) {
            long chunkDataPos = dataInputStream.getPosition() + TivoStreamChunk.CHUNK_HEADER_SIZE;
            chunks[i] = new TivoStreamChunk(dataInputStream);
            if (!chunks[i].read()) {
                return false;
            }
            if (chunks[i].isEncrypted()) {
                int offset = (int) (chunkDataPos - metaPosition);
                chunks[i].decryptMetadata(metaDecoder, offset);
                metaPosition = chunkDataPos + chunks[i].getDataSize();
            } else {
                decoder = new TuringDecoder(chunks[i].getKey(mak));
                metaDecoder = new TuringDecoder(chunks[i].getMetadataKey(mak));
            }
            logger.debug("Chunk {}: {}", i, chunks[i]);
        }
        return true;
    }

    private boolean processVideo(CountingDataInputStream dataInputStream) {
        StreamDecoder streamDecoder;
        logger.debug("File format: " + header.getFormat());
        switch (header.getFormat()) {
            case PROGRAM_STREAM:
                streamDecoder = new ProgramStreamDecoder(decoder, header.getMpegOffset(), dataInputStream, outputStream);
                break;
            case TRANSPORT_STREAM:
                streamDecoder = new TransportStreamDecoder(decoder, header.getMpegOffset(), dataInputStream,
                        outputStream, compatibilityMode);
                break;
            default:
                logger.error("Error: unknown file format.");
                return false;
        }
        return streamDecoder.process();
    }

    public List<Document> getMetadata() {
        List<Document> metadataChunks = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();

            for (TivoStreamChunk chunk : chunks) {
                String data = chunk.getDataString();
                Document doc = builder.parse(new InputSource(new StringReader(data)));
                metadataChunks.add(doc);
            }
        } catch (IOException | ParserConfigurationException | SAXException e) {
            logger.error("Error parsing XML metadata: ", e);
        }
        return metadataChunks;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("[TivoStream");
        sb.append(String.format(" header=%s", header));
        for (int i = 0; i < header.getNumChunks(); i++) {
            sb.append(String.format(" chunk[%d]=%s", i, chunks[i]));
        }

        sb.append("]");

        return sb.toString();
    }

    @SuppressWarnings("unused")
    public void printChunkPayloads() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < header.getNumChunks(); i++) {
            sb.append(String.format("<START OF CHUNK %d PAYLOAD>%s<END OF PAYLOAD>%n", i, chunks[i].getDataString()));
        }

        logger.info(sb.toString());
    }

    public enum Format {
        PROGRAM_STREAM,
        TRANSPORT_STREAM
    }
}
