package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import com.madgag.gif.fmsware.GifDecoder;
import org.junit.jupiter.api.Test;

class GifNullInputTest
{
    @Test void nullBufferedStreamReturnsOpenErrorAndResetsPriorFrames()
    {
        GifDecoder decoder = new GifDecoder();
        assertEquals(GifDecoder.STATUS_OK, decoder.read(new ByteArrayInputStream(GifFrameSemanticsTest.encoded(2, 2))));
        assertEquals(2, decoder.getFrameCount());
        assertEquals(GifDecoder.STATUS_OPEN_ERROR, decoder.read((BufferedInputStream) null));
        assertEquals(0, decoder.getFrameCount());
        assertEquals(GifDecoder.STATUS_OK, decoder.read(new ByteArrayInputStream(GifFrameSemanticsTest.encoded(2, 2))));
    }
    @Test void nullGenericStreamReturnsOpenErrorAndDecoderCanBeReused()
    {
        GifDecoder decoder = new GifDecoder();
        assertEquals(GifDecoder.STATUS_OPEN_ERROR, decoder.read((InputStream) null));
        assertEquals(0, decoder.getFrameCount());
        assertEquals(GifDecoder.STATUS_OK, decoder.read(new ByteArrayInputStream(GifFrameSemanticsTest.encoded(2, 2))));
    }
    @Test void malformedNonNullStreamsStillCloseExactlyOnce()
    {
        for (boolean buffered : new boolean[] {false, true})
        {
            class Stream extends ByteArrayInputStream
            {
                int closes;
                Stream() { super(new byte[] {1, 2, 3}); }
                @Override public void close() { this.closes++; }
            }
            Stream stream = new Stream();
            GifDecoder decoder = new GifDecoder();
            int status = buffered ? decoder.read(new BufferedInputStream(stream)) : decoder.read(stream);
            assertEquals(GifDecoder.STATUS_FORMAT_ERROR, status);
            assertEquals(1, stream.closes);
        }
    }
}
