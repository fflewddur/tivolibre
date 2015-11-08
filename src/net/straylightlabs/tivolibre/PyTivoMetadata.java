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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Model PyTivo's metadata format, as documented at http://pytivo.sourceforge.net/wiki/index.php/Metadata.
 */
public class PyTivoMetadata {
    private final ZonedDateTime airDate;
    private final ZonedDateTime originalAirDate;
    private final int movieYear;
    private final String title;
    private final String seriesTitle;
    private final String episodeTitle;
    private final String description;
    private final boolean isEpisode;
    private final String seriesId;
    private final String programId;
    private final int episodeNumber;
//    private final int channelNumber;
//    private final String channelCallSign;
    private final int showingBits;
    private final int tvRating;
    private final int starRating;
    private final String mpaaRating;
    private final int colorCode;
    private final List<String> programGenres;
    private final List<String> actors;
    private final List<String> guestStars;
    private final List<String> directors;
    private final List<String> execProducers;
    private final List<String> producers;
    private final List<String> writers;
    private final List<String> hosts;
    private final List<String> choreographers;
//    private final int partCount;
//    private final int partIndex;

    private final static Logger logger = LoggerFactory.getLogger(PyTivoMetadata.class);

    public static PyTivoMetadata createFromMetadata(List<Document> xmlDocs) {
        Builder builder = new Builder();

        boolean foundElement = false;
        for (Document doc : xmlDocs) {
            NodeList showings = doc.getElementsByTagName("showing");
            if (showings == null || foundElement) {
                continue;
            }

            builder = new Builder();
            for (int i = 0; i < showings.getLength() && !foundElement; i++) {
                Node node = showings.item(i);
                if (nodeEquals(node, "showing")) {
                    foundElement = parseShowingNode(node, builder);
                }
            }
        }

        return builder.build();
    }

    private static boolean nodeEquals(Node node, String name) {
        return node.getNodeName().equalsIgnoreCase(name);
    }

    private static boolean parseShowingNode(Node element, Builder builder) {
        logger.info("parseShowingNode()");
        boolean foundUniqueId = false;
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            logger.debug("node name = {}", node.getNodeName());
            if (nodeEquals(node, "showingBits")) {
                int showingBits = Integer.parseInt(node.getAttributes().getNamedItem("value").getNodeValue());
                builder.addShowingBits(showingBits);
            } else if (nodeEquals(node, "time")) {
                builder.airDate(node.getTextContent());
            } else if (nodeEquals(node, "tvRating")) {
                int rating = Integer.parseInt(node.getAttributes().getNamedItem("value").getNodeValue());
                builder.tvRating(rating);
            } else if (nodeEquals(node, "program")) {
                foundUniqueId = parseProgramNode(node, builder);
            }
        }
        return foundUniqueId;
    }

    private static boolean parseProgramNode(Node program, Builder builder) {
        logger.info("parseProgramNode()");
        boolean foundUniqueId = false;
        for (Node node = program.getFirstChild(); node != null; node = node.getNextSibling()) {
            logger.debug("program node name = {}", node.getNodeName());
            if (nodeEquals(node, "showingBits")) {
                int showingBits = Integer.parseInt(node.getAttributes().getNamedItem("value").getNodeValue());
                builder.addShowingBits(showingBits);
            } else if (nodeEquals(node, "originalAirDate")) {
                builder.originalAirDate(node.getTextContent());
            } else if (nodeEquals(node, "title")) {
                builder.title(node.getTextContent());
            } else if (nodeEquals(node, "isEpisode")) {
                builder.isEpisode(node.getTextContent());
            } else if (nodeEquals(node, "episodeNumber")) {
                builder.episodeNumber(node.getTextContent());
            } else if (nodeEquals(node, "episodeTitle")) {
                builder.episodeTitle(node.getTextContent());
            } else if (nodeEquals(node, "movieYear")) {
                builder.movieYear(node.getTextContent());
            } else if (nodeEquals(node, "mpaaRating")) {
                builder.mpaaRating(node.getTextContent());
            } else if (nodeEquals(node, "starRating")) {
                builder.starRating(node.getAttributes().getNamedItem("value").getNodeValue());
            } else if (nodeEquals(node, "colorCode")) {
                builder.colorCode(node.getAttributes().getNamedItem("value").getNodeValue());
            } else if (nodeEquals(node, "description")) {
                builder.description(node.getTextContent());
            } else if (nodeEquals(node, "uniqueId")) {
                foundUniqueId = true;
                builder.programId(node.getTextContent());
            } else if (nodeEquals(node, "series")) {
                foundUniqueId |= parseSeriesNode(node, builder);
            } else if (nodeEquals(node, "vActor")) {
                parseListNode(node, builder.actors);
            } else if (nodeEquals(node, "vChoreographer")) {
                parseListNode(node, builder.choreographers);
            } else if (nodeEquals(node, "vDirector")) {
                parseListNode(node, builder.directors);
            } else if (nodeEquals(node, "vExecProducer")) {
                parseListNode(node, builder.execProducers);
            } else if (nodeEquals(node, "vProgramGenre")) {
                parseListNode(node, builder.programGenres);
            } else if (nodeEquals(node, "vGuestStar")) {
                parseListNode(node, builder.guestStars);
            } else if (nodeEquals(node, "vHost")) {
                parseListNode(node, builder.hosts);
            } else if (nodeEquals(node, "vProducer")) {
                parseListNode(node, builder.producers);
            } else if (nodeEquals(node, "vWriter")) {
                parseListNode(node, builder.writers);
            }
        }
        return foundUniqueId;
    }

    private static void parseListNode(Node listNode, List<String> list) {
        for (Node node = listNode.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (nodeEquals(node, "element")) {
                String val = node.getTextContent().trim();
                if (!val.isEmpty()) {
                    list.add(node.getTextContent());
                }
            }
        }
    }

    private static boolean parseSeriesNode(Node series, Builder builder) {
        boolean foundUniqueId = false;
        for (Node node = series.getFirstChild(); node != null; node = node.getNextSibling()) {
            logger.debug("series node name = {}", node.getNodeName());
            if (nodeEquals(node, "seriesTitle")) {
                builder.seriesTitle(node.getTextContent());
            } else if (nodeEquals(node, "uniqueId")) {
                foundUniqueId = true;
                builder.seriesId(node.getTextContent());
            }
        }
        return foundUniqueId;
    }

    private PyTivoMetadata(Builder builder) {
        airDate = builder.airDate;
        originalAirDate = builder.originalAirDate;
        movieYear = builder.movieYear;
        if (builder.seriesTitle != null && builder.episodeTitle != null) {
            title = builder.seriesTitle + " - " + builder.episodeTitle;
        } else if (builder.title != null) {
            title = builder.title;
        } else if (builder.seriesTitle != null) {
            title = builder.seriesTitle;
        } else if (builder.episodeTitle != null) {
            title = builder.episodeTitle;
        } else {
            title = "Unknown";
        }
        seriesTitle = builder.seriesTitle;
        episodeTitle = builder.episodeTitle;
        description = builder.description;
        isEpisode = builder.isEpisode;
        seriesId = builder.seriesId;
        programId = builder.programId;
        episodeNumber = builder.episodeNumber;
//        channelNumber = builder.channelNumber;
//        channelCallSign = builder.channelCallSign;
        showingBits = builder.showingBits;
        tvRating = builder.tvRating;
        starRating = builder.starRating;
        mpaaRating = builder.mpaaRating;
        colorCode = builder.colorCode;
        programGenres = new ArrayList<>(builder.programGenres);
        actors = new ArrayList<>(builder.actors);
        guestStars = new ArrayList<>(builder.guestStars);
        directors = new ArrayList<>(builder.directors);
        execProducers = new ArrayList<>(builder.execProducers);
        producers = new ArrayList<>(builder.producers);
        writers = new ArrayList<>(builder.writers);
        hosts = new ArrayList<>(builder.hosts);
        choreographers = new ArrayList<>(builder.choreographers);
//        partCount = builder.partCount;
//        partIndex = builder.partIndex;
    }

    public boolean writeToFile(Path filePath) {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(filePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE))) {
            writeString(writer, "title", title);
            writeString(writer, "seriesTitle", seriesTitle);
            writeString(writer, "episodeTitle", episodeTitle);
            writeString(writer, "time", airDate);
            writeString(writer, "originalAirDate", originalAirDate);
            writeString(writer, "description", description);
            writeString(writer, "isEpisode", isEpisode);
            writeString(writer, "seriesId", seriesId);
            writeString(writer, "programId", programId);
            writeString(writer, "mpaaRating", mpaaRating);

            writeInt(writer, "episodeNumber", episodeNumber);
            writeInt(writer, "tvRating", tvRating, "x");
            writeInt(writer, "starRating", starRating, "x");
            writeInt(writer, "showingBits", showingBits);
            writeInt(writer, "colorCode", colorCode, "x");
//            if (partCount > 0 && partIndex > 0) {
//                writeInt(writer, "partCount", partCount);
//                writeInt(writer, "partIndex", partIndex);
//            }
            writeInt(writer, "movieYear", movieYear);

            writeList(writer, "vActor", actors);
            writeList(writer, "vProgramGenre", programGenres);
            writeList(writer, "vDirector", directors);
            writeList(writer, "vGuestStar", guestStars);
            writeList(writer, "vExecProducer", execProducers);
            writeList(writer, "vProducer", producers);
            writeList(writer, "vWriter", writers);
            writeList(writer, "vHost", hosts);
            writeList(writer, "vChoreographer", choreographers);

        } catch (IOException e) {
            logger.error("Error writing to file '{}': ", filePath, e);
            return false;
        }

        return true;
    }

    private void writeString(PrintWriter writer, String field, Object val) {
        if (val != null) {
            writer.format("%s : %s%n", field, val);
        }
    }

    private void writeInt(PrintWriter writer, String field, int val) {
        writeInt(writer, field, val, "");
    }

    private void writeInt(PrintWriter writer, String field, int val, String prefix) {
        if (val > 0) {
            writer.format("%s : %s%d%n", field, prefix, val);
        }
    }

    private void writeList(PrintWriter writer, String field, List<String> list) {
        if (list.size() > 0) {
            list.stream().forEach(e -> writer.format("%s : %s%n", field, e));
        }
    }

    static class Builder {
        private ZonedDateTime airDate;
        private ZonedDateTime originalAirDate;
        private int movieYear;
        private String title;
        private String episodeTitle;
        private String description;
        private boolean isEpisode;
        private String seriesId;
        private String programId;
        private String seriesTitle;
        private int episodeNumber;
//        private int channelNumber;
//        private String channelCallSign;
        private int showingBits;
        private int tvRating;
        private int starRating;
        private String mpaaRating;
        private int colorCode;
        private List<String> programGenres;
        private List<String> actors;
        private List<String> guestStars;
        private List<String> directors;
        private List<String> execProducers;
        private List<String> producers;
        private List<String> writers;
        private List<String> hosts;
        private List<String> choreographers;
//        private int partCount;
//        private int partIndex;

        public Builder() {
            programGenres = new ArrayList<>();
            actors = new ArrayList<>();
            guestStars = new ArrayList<>();
            directors = new ArrayList<>();
            execProducers = new ArrayList<>();
            producers = new ArrayList<>();
            writers = new ArrayList<>();
            hosts = new ArrayList<>();
            choreographers = new ArrayList<>();
        }

        public PyTivoMetadata build() {
            return new PyTivoMetadata(this);
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder addShowingBits(int bits) {
            showingBits += bits;
            return this;
        }

        public Builder airDate(String date) {
            airDate = ZonedDateTime.parse(date);
            return this;
        }

        public Builder originalAirDate(String date) {
            originalAirDate = ZonedDateTime.parse(date);
            return this;
        }

        public Builder isEpisode(String val) {
            isEpisode = Boolean.parseBoolean(val);
            return this;
        }

        public Builder colorCode(String val) {
            colorCode = Integer.parseInt(val);
            return this;
        }

        public Builder seriesId(String val) {
            seriesId = val;
            return this;
        }

        public Builder programId(String val) {
            programId = val;
            return this;
        }

        public Builder seriesTitle(String val) {
            seriesTitle = val;
            return this;
        }

        public Builder description(String val) {
            description = val;
            return this;
        }

        public Builder episodeNumber(String val) {
            episodeNumber = Integer.parseInt(val);
            return this;
        }

        public Builder episodeTitle(String val) {
            episodeTitle = val;
            return this;
        }

        public Builder tvRating(int val) {
            tvRating = val;
            return this;
        }

        public Builder movieYear(String val) {
            movieYear = Integer.parseInt(val);
            return this;
        }

        public Builder starRating(String val) {
            starRating = Integer.parseInt(val);
            return this;
        }

        public Builder mpaaRating(String val) {
            mpaaRating = val;
            return this;
        }
    }
}
