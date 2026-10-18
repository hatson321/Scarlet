package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import com.madgag.gif.fmsware.GifDecoder;

class GifAllocationLimitsTest {
    private static final byte[] SINGLE = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==");

    @Test void normalTinyGifStillDecodes() {
        GifDecoder d = new GifDecoder();
        assertEquals(GifDecoder.STATUS_OK, d.read(new ByteArrayInputStream(SINGLE)));
        assertEquals(1, d.getFrameCount());
    }

    @Test void rejectsCanvasBeforeAnyFrameAllocation() {
        byte[] data = SINGLE.clone();
        data[6] = 1; data[7] = 8; // 2049 pixels, only 1 pixel of compressed data.
        GifDecoder d = new GifDecoder();
        assertEquals(GifDecoder.STATUS_FORMAT_ERROR, d.read(new ByteArrayInputStream(data)));
        assertEquals(0, d.getFrameCount());
        assertNull(d.getImage());
    }

    @Test void rejectsOversizedFrameRectangleBeforePixelArrayAllocation() {
        byte[] data = SINGLE.clone();
        data[24] = 1; data[25] = 8;
        GifDecoder d = new GifDecoder();
        assertEquals(GifDecoder.STATUS_FORMAT_ERROR, d.read(new ByteArrayInputStream(data)));
        assertEquals(0, d.getFrameCount());
    }

    @Test void frameCountAndCumulativeMemoryAreEnforcedBeforeNextFrame() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(SINGLE, 0, SINGLE.length - 1);
        bytes.write(SINGLE, 19, SINGLE.length - 20);
        bytes.write(0x3b);
        GifDecoder countLimited = new GifDecoder(2048, 1, Gifs.MAX_GIF_DECODED_BYTES);
        assertEquals(GifDecoder.STATUS_FORMAT_ERROR, countLimited.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(1, countLimited.getFrameCount());
        GifDecoder memoryLimited = new GifDecoder(2048, 256, 1024L * 1024 + 65536 + 10);
        assertEquals(GifDecoder.STATUS_FORMAT_ERROR, memoryLimited.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(1, memoryLimited.getFrameCount());
    }
}
