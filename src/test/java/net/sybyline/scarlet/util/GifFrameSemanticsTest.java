package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import com.madgag.gif.fmsware.*;
import org.junit.jupiter.api.Test;

class GifFrameSemanticsTest {
    static byte[] encoded(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AnimatedGifEncoder encoder = new AnimatedGifEncoder();
        assertTrue(encoder.start(out));
        encoder.setDelay(0);
        BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        frame.setRGB(0, 0, 0xff00ff00);
        assertTrue(encoder.addFrame(frame)); assertTrue(encoder.addFrame(frame));
        assertTrue(encoder.finish()); return out.toByteArray();
    }
    @Test void extremeAspectAndUnspecifiedTimingProduceVisibleFiniteSprites() throws Exception {
        for (int[] size : new int[][]{{2048,1},{1,2048},{2,2}}) {
            GifDecoder decoder = new GifDecoder();
            assertEquals(GifDecoder.STATUS_OK, decoder.read(new ByteArrayInputStream(encoded(size[0], size[1]))));
            Gifs.VrcSpriteSheet sheet = Gifs.VrcSpriteSheet.of("fixture.gif", decoder);
            assertEquals(10f, sheet.fps); assertEquals(2, sheet.renderedFrames);
            assertTrue(sheet.renderedFrameBounds.width >= 1); assertTrue(sheet.renderedFrameBounds.height >= 1);
            assertEquals(1024, sheet.image.getWidth());
            int x=sheet.renderedFrameBounds.x, y=sheet.renderedFrameBounds.y;
            assertNotEquals(0, sheet.image.getRGB(x,y) >>> 24);
        }
    }
    @Test void omittedControlExtensionDoesNotInheritPreviousFrameControls() {
        byte[] original=encoded(2,2);
        ByteArrayOutputStream modified=new ByteArrayOutputStream(); int controls=0;
        for(int i=0;i<original.length;) {
            if(i+7<original.length && original[i]==0x21 && original[i+1]==(byte)0xf9 && original[i+2]==4) {
                controls++;
                if(controls==1) { original[i+3]=9; original[i+4]=5; }
                if(controls==2) { i+=8; continue; }
            }
            modified.write(original[i++]);
        }
        assertEquals(2,controls);
        GifDecoder decoder=new GifDecoder();
        assertEquals(GifDecoder.STATUS_OK,decoder.read(new ByteArrayInputStream(modified.toByteArray())));
        assertEquals(50,decoder.getDelay(0)); assertEquals(0,decoder.getDelay(1));
        assertNotEquals(0,decoder.getFrame(1).getRGB(0,0) >>> 24);
    }
}
