package net.straylightlabs.tivolibre;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Hold a collection of MPEG-TS frames that together form a logical packet.
 * This allows us to verify the PTS and DTS order of TS frames we output.
 */
public class FrameGroup implements Comparable<FrameGroup> {
    private final List<byte[]> frames;
    private long pts;
    private long dts;
    private final long order;

    static final int PTS_DEFAULT = 0;
    static final int DTS_DEFAULT = 0;
    private static long orderCounter = 0;

    private final static Logger logger = LoggerFactory.getLogger(FrameGroup.class);

    public FrameGroup(long pts, long dts) {
        frames = new ArrayList<>();
        this.pts = pts;
        this.dts = dts;
        this.order = orderCounter++;
    }

    public FrameGroup() {
        this(PTS_DEFAULT, DTS_DEFAULT);
    }

    public void updateTimestamps(long pts, long dts) {
        if (pts != PTS_DEFAULT && this.pts != PTS_DEFAULT) {
            logger.error("DIFFERENT PTS VALUES FOR SAME PACKET: {} vs {}", this.pts, pts);
        }
        if (dts != DTS_DEFAULT && this.dts != DTS_DEFAULT) {
            logger.error("DIFFERENT DTS VALUES FOR SAME PACKET: {} vs {}", this.dts, dts);
        }
        if (this.pts == PTS_DEFAULT && pts != PTS_DEFAULT) {
            this.pts = pts;
        }
        if (this.dts == DTS_DEFAULT && dts != DTS_DEFAULT) {
            this.dts = dts;
        }
    }

    public long getPTS() {
        return pts;
    }

    public long getDTS() {
        return dts;
    }

    public void addFrame(byte[] frame) {
        frames.add(frame);
    }

    public void writeTo(OutputStream stream) throws IOException {
//        logger.debug("Writing frame group with DTS={}, PTS={}, order={}", dts, pts, order);
        for (byte[] frame : frames) {
            stream.write(frame);
        }
    }

    /** If both frames have a DTS, compare them based on DTS. If only one does, compare its DTS against the other
     * frame's PTS. If both frames only have PTS, compare them based on PTS. If one or both frames is missing PTS,
     * compare them based on order.
     */
    @Override
    public int compareTo(FrameGroup o) {
        if ((dts == DTS_DEFAULT && pts == PTS_DEFAULT ) || (o.dts == DTS_DEFAULT && o.pts == PTS_DEFAULT)) {
            // At least one frame is missing both PTS and DTS; the first one we saw should be ordered before the other
            if (order < o.order) {
                return -1;
            } else if (order > o.order) {
                return 1;
            } else {
                return 0;
            }
        } else if (dts == DTS_DEFAULT && o.dts == DTS_DEFAULT) {
            // Neither frame has DTS, so order by PTS
            if (pts < o.pts) {
                return -1;
            } else if (pts > o.pts) {
                return 1;
            } else {
                return 0;
            }
        } else {
            // At least one frame has DTS; compare the DTS values against one another, unless one frame only has PTS
            long cmpValue = dts, otherCmpValue = o.dts;
            if (cmpValue == DTS_DEFAULT) {
                cmpValue = pts;
            } else if (otherCmpValue == DTS_DEFAULT){
                otherCmpValue = o.pts;
            }
            if (cmpValue < otherCmpValue) {
                return -1;
            } else if (cmpValue > otherCmpValue) {
                return 1;
            } else {
                return 0;
            }
        }
    }
}
