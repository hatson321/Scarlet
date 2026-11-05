package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import io.github.vrchatapi.model.MIMEType;
import io.github.vrchatapi.model.ModelFile;
import org.junit.jupiter.api.Test;

class ScarletCalendarCoverTest
{
    static class Input extends ByteArrayInputStream
    {
        boolean closed;
        Input() { super(new byte[]{1, 2, 3}); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
    @Test void supportedCoversHaveCorrectMimeAndCloseTheirStream() throws Exception
    {
        MIMEType[] types = {MIMEType.IMAGE_GIF, MIMEType.IMAGE_JPEG, MIMEType.IMAGE_JPG, MIMEType.IMAGE_PNG, MIMEType.IMAGE_WEBP};
        String[] prefixes = {"gif", "jpeg", "jpeg", "png", "webp"};
        for (int i = 0; i < types.length; i++)
        {
            Input input = new Input();
            assertTrue(ScarletCalendar.loadCoverImage(new ModelFile().mimeType(types[i]), () -> input)
                .getEncoding().startsWith("data:image/" + prefixes[i] + ";base64,"));
            assertTrue(input.closed);
        }
    }
    @Test void unavailableMimeDoesNotDownloadAndReadFailureClosesStream() throws Exception
    {
        assertNull(ScarletCalendar.loadCoverImage(new ModelFile().mimeType(null), () -> { throw new AssertionError("Unexpected download"); }));
        final boolean[] closed = {false};
        InputStream broken = new InputStream() {
            public int read() throws IOException { throw new IOException("synthetic failed read"); }
            public void close() { closed[0] = true; }
        };
        assertThrows(IOException.class, () -> ScarletCalendar.loadCoverImage(new ModelFile().mimeType(MIMEType.IMAGE_PNG), () -> broken));
        assertTrue(closed[0]);
    }
}
