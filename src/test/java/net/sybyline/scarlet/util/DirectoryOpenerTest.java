package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DirectoryOpenerTest
{
    static class FakeProcess extends Process
    {
        boolean exited = true; int code = 3, exitReads;
        public java.io.OutputStream getOutputStream() { throw new AssertionError(); }
        public java.io.InputStream getInputStream() { throw new AssertionError(); }
        public java.io.InputStream getErrorStream() { throw new AssertionError(); }
        public int waitFor() { throw new AssertionError("Must use bounded wait"); }
        public boolean waitFor(long time, TimeUnit unit) { assertEquals(750, unit.toMillis(time)); return exited; }
        public int exitValue() { exitReads++; return code; }
        public void destroy() { throw new AssertionError("Do not close a resident file manager"); }
    }
    @Test void quickFailureAllowsFallbackAndResidentManagerDoesNotBlock() throws Exception
    {
        FakeProcess process = new FakeProcess();
        assertFalse(MiscUtils.AWTDesktop.openerAccepted(process));
        process.code = 0;
        assertTrue(MiscUtils.AWTDesktop.openerAccepted(process));
        process.exited = false;
        assertTrue(MiscUtils.AWTDesktop.openerAccepted(process));
        assertEquals(2, process.exitReads);
    }
}
