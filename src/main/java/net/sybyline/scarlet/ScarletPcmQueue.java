package net.sybyline.scarlet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded PCM clips, invalidated whenever their voice destination changes. */
final class ScarletPcmQueue
{
    static final int FRAME_BYTES = 3840, MAX_CLIP_FRAMES = 1500, MAX_QUEUED_FRAMES = 3000;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final LongSupplier clock;
    private long epoch;
    ScarletPcmQueue() { this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime())); }
    ScarletPcmQueue(LongSupplier clock) { this.clock = clock; }
    private static final class Frame
    {
        final ByteBuffer data; final long time; final java.util.function.BooleanSupplier session;
        Frame(ByteBuffer data, long time, java.util.function.BooleanSupplier session) { this.data = data; this.time = time; this.session = session; }
    }
    synchronized long epoch() { return this.epoch; }
    synchronized void reset() { this.epoch++; this.frames.clear(); }
    synchronized boolean offer(long expectedEpoch, List<ByteBuffer> clip)
    { return this.offer(expectedEpoch, clip, () -> true); }

    synchronized boolean offer(long expectedEpoch, List<ByteBuffer> clip, java.util.function.BooleanSupplier session)
    {
        this.expire();
        if (!session.getAsBoolean() || expectedEpoch != this.epoch || clip.size() > MAX_CLIP_FRAMES || this.frames.size() + clip.size() > MAX_QUEUED_FRAMES) return false;
        for (ByteBuffer frame : clip) if (frame == null || frame.remaining() != FRAME_BYTES) return false;
        long now = this.clock.getAsLong();
        for (ByteBuffer frame : clip) this.frames.addLast(new Frame(frame, now, session));
        return true;
    }
    private void expire()
    {
        long now = this.clock.getAsLong();
        while (!this.frames.isEmpty() && (!this.frames.peekFirst().session.getAsBoolean()
            || now < this.frames.peekFirst().time || now - this.frames.peekFirst().time >= 60000)) this.frames.removeFirst();
    }
    synchronized int size() { this.expire(); return this.frames.size(); }
    synchronized ByteBuffer poll() { this.expire(); Frame next = this.frames.pollFirst(); return next == null ? null : next.data; }

    static List<ByteBuffer> decode(InputStream input) throws IOException
    {
        List<ByteBuffer> result = new ArrayList<>(); long start = System.nanoTime();
        while (true)
        {
            byte[] bytes = new byte[FRAME_BYTES]; int total = 0;
            while (total < bytes.length)
            {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - start > TimeUnit.SECONDS.toNanos(5)) throw new IOException("PCM decoding timed out");
                int read = input.read(bytes, total, bytes.length - total);
                if (read < 0) break;
                if (read == 0) throw new IOException("PCM decoder made no progress");
                total += read;
            }
            if (total == 0) return result;
            if (result.size() >= MAX_CLIP_FRAMES) throw new IOException("Audio clip exceeds 30 seconds");
            result.add(ByteBuffer.wrap(bytes));
            if (total < bytes.length) return result;
        }
    }
}
