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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Model PyTivo's metadata format, as documented at http://pytivo.sourceforge.net/wiki/index.php/Metadata.
 */
public class PyTivoMetadata {
    private final LocalDateTime airDate;
    private final LocalDateTime originalAirDate;
    private final int movieYear;
    private final String title;
    private final String episodeTitle;
    private final String description;
    private final boolean isEpisode;
    private final String seriesId;
    private final int episodeNumber;
    private final int channelNumber;
    private final String channelCallSign;
    private final int showingBits;
    private final String tvRating;
    private final String starRating;
    private final String mpaaRating;
    private final String colorCode;
    private final List<String> programGenres;
    private final List<String> actors;
    private final List<String> guestStars;
    private final List<String> directors;
    private final List<String> execProducers;
    private final List<String> producers;
    private final List<String> writers;
    private final List<String> hosts;
    private final List<String> choreographers;
    private final int partCount;
    private final int partIndex;

    private final static Logger logger = LoggerFactory.getLogger(PyTivoMetadata.class);

    public static PyTivoMetadata createFromMetadata(List<Document> xmlDocs) {
        Builder builder = new Builder();

        for (Document doc : xmlDocs) {
            NodeList showings = doc.getElementsByTagName("vActualShowing");
            if (showings == null) {
                continue;
            }

            for (int i = 0; i < showings.getLength(); i++) {
                Node node = showings.item(i);
                if (node.getNodeName().equals("element")) {
                    parseElementNode(node, builder);
                }
            }
        }

        return builder.build();
    }

    private static void parseElementNode(Node element, Builder builder) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeName().equals("showingBits")) {
                logger.debug("showingBits value: {}", node.getAttributes().getNamedItem("value").getNodeValue());
            } else if (node.getNodeName().equals("time")) {
                logger.debug("time value: {}", node.getTextContent());
            } else if (node.getNodeName().equals("program")) {
                logger.debug("program value: {}", node.getTextContent());
            }
        }
    }

    private PyTivoMetadata(Builder builder) {
        airDate = builder.airDate;
        originalAirDate = builder.originalAirDate;
        movieYear = builder.movieYear;
        title = builder.title;
        episodeTitle = builder.episodeTitle;
        description = builder.description;
        isEpisode = builder.isEpisode;
        seriesId = builder.seriesId;
        episodeNumber = builder.episodeNumber;
        channelNumber = builder.channelNumber;
        channelCallSign = builder.channelCallSign;
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
        partCount = builder.partCount;
        partIndex = builder.partIndex;
    }

    public boolean writeToFile(Path filePath) {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(filePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE))) {
            writer.format("title : %s%n", title);
        } catch (IOException e) {
            logger.error("Error writing to file '{}': ", filePath, e);
            return false;
        }

        return true;
    }

    static class Builder {
        private LocalDateTime airDate;
        private LocalDateTime originalAirDate;
        private int movieYear;
        private String title;
        private String episodeTitle;
        private String description;
        private boolean isEpisode;
        private String seriesId;
        private int episodeNumber;
        private int channelNumber;
        private String channelCallSign;
        private int showingBits;
        private String tvRating;
        private String starRating;
        private String mpaaRating;
        private String colorCode;
        private List<String> programGenres;
        private List<String> actors;
        private List<String> guestStars;
        private List<String> directors;
        private List<String> execProducers;
        private List<String> producers;
        private List<String> writers;
        private List<String> hosts;
        private List<String> choreographers;
        private int partCount;
        private int partIndex;

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
    }
}
