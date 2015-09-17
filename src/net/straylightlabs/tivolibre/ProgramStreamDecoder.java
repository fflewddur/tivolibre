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

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

class ProgramStreamDecoder extends StreamDecoder {
    private ProgramStream programStream;

    public ProgramStreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
                                OutputStream outputStream) {
        super(decoder, mpegOffset, inputStream, outputStream);

        programStream = new ProgramStream(inputStream, outputStream, decoder);
    }

    @Override
    boolean process() {
        int marker = 0xFFFFFFFF;
        try {
            advanceToMpegOffset();
            boolean first = true;
            int code = 0x00;
            while (true) {
                if ((marker & 0xFFFFFF00) == 0x100) {
                    int result = programStream.processFrame(code);
                    if (result == 1) {
                        marker = 0xFFFFFFFF;
                    } else if (result == 0) {
                        outputStream.write(code);
                    } else if (result < 0) {
                        TivoDecoder.logger.error("Error processing frame");
                        return false;
                    }
                } else if (!first) {
                    outputStream.write(code);
                }

                marker <<= 8;
                code = inputStream.readUnsignedByte();
                marker |= code;

                first = false;
            }
        } catch (EOFException e) {
            TivoDecoder.logger.info("End of file reached");
            return true;
        } catch (IOException e) {
            TivoDecoder.logger.error("Error reading program stream: ", e);
        }

        return false;
    }
}
