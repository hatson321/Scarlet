package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ScarletPcmQueueTest
{
    @Test void clipsAreBoundedAndPartialFramesPadded() throws Exception
    {
        var frames = ScarletPcmQueue.decode(new ByteArrayInputStream(new byte[]{1,2,3}));
        assertEquals(1, frames.size()); assertEquals(ScarletPcmQueue.FRAME_BYTES, frames.get(0).remaining());
        assertEquals(0, frames.get(0).get(3));
        InputStream infinite = new InputStream() {
            public int read() { return 0; }
            public int read(byte[] b, int off, int len) { return len; }
        };
        assertThrows(IOException.class, () -> ScarletPcmQueue.decode(infinite));
        InputStream stalled = new InputStream() {
            public int read() { return 0; }
            public int read(byte[] b, int off, int len) { return 0; }
        };
        assertThrows(IOException.class, () -> ScarletPcmQueue.decode(stalled));
    }

    @Test void overflowIsAllOrNothingAndOldDestinationsOrStaleAudioNeverPlay()
    {
        AtomicLong clock = new AtomicLong(); ScarletPcmQueue queue = new ScarletPcmQueue(clock::get);
        List<ByteBuffer> clip = new ArrayList<>();
        for (int i=0;i<1500;i++) clip.add(ByteBuffer.allocate(ScarletPcmQueue.FRAME_BYTES));
        long old = queue.epoch(); assertTrue(queue.offer(old, clip)); assertTrue(queue.offer(old, clip));
        assertFalse(queue.offer(old, clip)); assertEquals(3000, queue.size());
        queue.reset(); assertFalse(queue.offer(old, clip)); assertNull(queue.poll());
        assertTrue(queue.offer(queue.epoch(), clip)); clock.set(60000); assertNull(queue.poll()); assertEquals(0, queue.size());
        java.util.concurrent.atomic.AtomicBoolean session = new java.util.concurrent.atomic.AtomicBoolean(true);
        assertTrue(queue.offer(queue.epoch(), clip, session::get)); session.set(false);
        assertNull(queue.poll()); assertFalse(queue.offer(queue.epoch(), clip, session::get));
    }
}
