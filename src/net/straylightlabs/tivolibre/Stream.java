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

abstract class Stream {
    protected int streamId;
    protected byte[] turingKey;
    protected int turingBlockNumber;
    protected int turingCrypted;

    public static final int KEY_LENGTH = 16;

    public Stream() {
        this.turingKey = new byte[KEY_LENGTH];
    }

    public void setStreamId(int val) {
        streamId = val;
    }

    protected boolean doHeader() {
        boolean keyIsSet = true;

        if ((turingKey[0] & 0x80) == 0)
            keyIsSet = false;
        if ((turingKey[1] & 0x40) == 0)
            keyIsSet = false;
        if ((turingKey[3] & 0x20) == 0)
            keyIsSet = false;
        if ((turingKey[4] & 0x10) == 0)
            keyIsSet = false;
        if ((turingKey[0xd] & 0x2) == 0)
            keyIsSet = false;
        if ((turingKey[0xf] & 0x1) == 0)
            keyIsSet = false;

        turingBlockNumber = (turingKey[0x1] & 0x3f) << 0x12;
        turingBlockNumber |= (turingKey[0x2] & 0xff) << 0xa;
        turingBlockNumber |= (turingKey[0x3] & 0xc0) << 0x2;
        turingBlockNumber |= (turingKey[0x3] & 0x1f) << 0x3;
        turingBlockNumber |= (turingKey[0x4] & 0xe0) >> 0x5;

        turingCrypted = (turingKey[0xb] & 0x03) << 0x1e;
        turingCrypted |= (turingKey[0xc] & 0xff) << 0x16;
        turingCrypted |= (turingKey[0xd] & 0xfc) << 0xe;
        turingCrypted |= (turingKey[0xd] & 0x01) << 0xf;
        turingCrypted |= (turingKey[0xe] & 0xff) << 0x7;
        turingCrypted |= (turingKey[0xf] & 0xfe) >> 0x1;

        return keyIsSet;
    }
}
