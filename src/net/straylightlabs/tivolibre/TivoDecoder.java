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

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Decodes a TiVo file to a standard MPEG file.
 */
public class TivoDecoder {
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final String mak;
    private final boolean compatibilityMode;
    private TivoStream tivoStream;

    public final static Logger logger;

    public final static String VERSION = "0.7.3";

    static {
        logger = LoggerFactory.getLogger(TivoDecoder.class.toString());
    }

    private TivoDecoder(InputStream inputStream, OutputStream outputStream, String mak, boolean compatibilityMode) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.mak = mak;
        this.compatibilityMode = compatibilityMode;
    }

    /**
     * DEPRECATED: Use the TivoDecoder.Builder class to create TivoDecoder objects.
     */
    @Deprecated
    public TivoDecoder(InputStream inputStream, OutputStream outputStream, String mak) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.mak = mak;
        this.compatibilityMode = false;
    }

    /**
     * Decode the specified @inputStream with @mak, printing the results to @outputStream.
     */
    public boolean decode() {
        verifyInternalState();
        tivoStream = new TivoStream(inputStream, outputStream, mak);
        tivoStream.setCompatibilityMode(compatibilityMode);
        return tivoStream.process();
    }

    /**
     * Decode only the metadata from @inputStream. Prints nothing to @outputStream.
     */
    public boolean decodeMetadata() {
        tivoStream = new TivoStream(inputStream, null, mak);
        tivoStream.setCompatibilityMode(compatibilityMode);
        tivoStream.setProcessVideo(false);
        return tivoStream.process();
    }

    /**
     * Most of this is already handled by Builder, but we need to ensure @outputStream exists for non-metadata processing.
     */
    private void verifyInternalState() {
        if (outputStream == null) {
            throw new IllegalStateException("Cannot decode a video without an OutputStream");
        }
    }

    /**
     * Return a list of XML Documents representing the recording metadata for the processed TiVo file.
     */
    public List<Document> getMetadata() {
        if (tivoStream == null) {
            throw new IllegalStateException("Cannot call getMetadata() before processing a TivoStream");
        }
        return tivoStream.getMetadata();
    }

    /**
     * Save the TiVo file's metadata in PyTivo's format.
     */
    public void saveMetadata(Path path) {
        if (tivoStream == null) {
            throw new IllegalStateException("Cannot call saveMetadata() before processing a TivoStream");
        }
        PyTivoMetadata metadata = PyTivoMetadata.createFromMetadata(getMetadata());
        metadata.writeToFile(path);
    }



    public static String bytesToHexString(byte[] bytes, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        sb.append("    ");
        for (int i = offset; i < offset + length; i++) {
            sb.append(String.format("%02X", bytes[i]));
            if ((i + 1) % 40 == 0) {
                sb.append("\n    ");
            } else if ((i + 1) % 4 == 0) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    public static String bytesToHexString(byte[] bytes) {
        return bytesToHexString(bytes, 0, bytes.length);
    }

    /**
     * Class for building TivoDecoder objects.
     */
    public static class Builder {
        private InputStream inputStream;
        private OutputStream outputStream;
        private String mak;
        private boolean compatibilityMode;

        public Builder input(InputStream is) {
            inputStream = is;
            return this;
        }

        public Builder output(OutputStream os) {
            outputStream = os;
            return this;
        }

        public Builder mak(String mak) {
            this.mak = mak;
            return this;
        }

        public Builder compatibilityMode(boolean val) {
            compatibilityMode = val;
            return this;
        }

        /**
         * Builds a new TivoDecoder instance from the list of given parameters.
         */
        public TivoDecoder build() {
            verifyInternalState();
            return new TivoDecoder(inputStream, outputStream, mak, compatibilityMode);
        }

        private void verifyInternalState() {
            if (inputStream == null) {
                throw new IllegalStateException("Cannot build a TivoDecoder without an InputStream");
            }
            if (mak == null) {
                throw new IllegalStateException("Cannot build a TivoDecoder without a MAK");
            }
        }
    }
}
