package net.sybyline.scarlet.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded wait/output for a child created by the caller; never discovers unrelated processes.
 * Descendant cleanup is best effort for handles observed while the child is alive. */
public final class BoundedProcess {
    private BoundedProcess() { }
    public static final int CANCELLED = 126, TIMED_OUT = 124, OUTPUT_LIMIT = 125;

    public static final class Result {
        public final int exitCode;
        public final String output;
        public final boolean terminated;
        Result(int exitCode, String output, boolean terminated) {
            this.exitCode = exitCode; this.output = output; this.terminated = terminated;
        }
    }

    public static Result await(Process child, long timeoutMillis, int maxBytes,
            BooleanSupplier cancelled, Consumer<String> output) throws InterruptedException {
        if (timeoutMillis <= 0 || maxBytes < 1) throw new IllegalArgumentException("Invalid process limits");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicBoolean tooMuch = new AtomicBoolean(), drainFailed = new AtomicBoolean();
        Thread drain = new Thread(() -> {
            try (InputStream in = child.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    synchronized (captured) {
                        int retained = Math.min(count, maxBytes - captured.size());
                        captured.write(buffer, 0, retained);
                        if (retained < count) { tooMuch.set(true); return; }
                    }
                    if (output != null) output.accept(new String(buffer, 0, count, StandardCharsets.UTF_8));
                }
            } catch (Exception ex) { drainFailed.set(true); }
        }, "Scarlet-child-output");
        drain.setDaemon(true);
        drain.start();
        java.util.Set<ProcessHandle> descendants = new java.util.HashSet<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        int code;
        try {
            while (true) {
                try { child.toHandle().descendants().forEach(descendants::add); }
                catch (RuntimeException ignored) { }
                if (cancelled.getAsBoolean()) { code = CANCELLED; break; }
                if (tooMuch.get() || drainFailed.get()) { code = OUTPUT_LIMIT; break; }
                long left = deadline - System.nanoTime();
                if (left <= 0) { code = TIMED_OUT; break; }
                if (child.waitFor(Math.max(1L, Math.min(100L, TimeUnit.NANOSECONDS.toMillis(left))), TimeUnit.MILLISECONDS)) {
                    code = child.exitValue(); break;
                }
            }
            boolean terminated = terminate(child, descendants);
            drain.join(250L);
            if (cancelled.getAsBoolean()) code = CANCELLED;
            else if (code != TIMED_OUT && (tooMuch.get() || drainFailed.get() || drain.isAlive())) code = OUTPUT_LIMIT;
            synchronized (captured) {
                return new Result(code, new String(captured.toByteArray(), StandardCharsets.UTF_8), terminated);
            }
        } catch (InterruptedException ex) {
            try { terminate(child, descendants); }
            catch (InterruptedException cleanupInterrupted) { ex.addSuppressed(cleanupInterrupted); }
            throw ex;
        } catch (RuntimeException ex) {
            // A broken cancellation callback or Process wrapper must not orphan an owned child.
            try { terminate(child, descendants); }
            catch (InterruptedException cleanupInterrupted) {
                Thread.currentThread().interrupt();
                ex.addSuppressed(cleanupInterrupted);
            }
            throw ex;
        }
    }

    static void terminate(Process child) throws InterruptedException {
        terminate(child, new java.util.HashSet<>());
    }

    private static boolean terminate(Process child, java.util.Set<ProcessHandle> descendants) throws InterruptedException {
        // ProcessHandle is available at Scarlet's documented Java 11 runtime floor.
        try { child.toHandle().descendants().forEach(descendants::add); }
        catch (RuntimeException ignored) { } // Some PTY wrappers do not implement toHandle.
        descendants.forEach(handle -> { try { if (handle.isAlive()) handle.destroy(); } catch (RuntimeException ignored) { } });
        try { if (aliveOrUnknown(child)) child.destroy(); } catch (RuntimeException ignored) { }
        boolean interrupted = false;
        try { child.waitFor(500L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ex) { interrupted = true; }
        catch (RuntimeException ignored) { }
        // Even an interrupted/broken grace wait must still reach the force-stop attempt.
        descendants.forEach(handle -> { try { if (handle.isAlive()) handle.destroyForcibly(); } catch (RuntimeException ignored) { } });
        try { if (aliveOrUnknown(child)) child.destroyForcibly(); } catch (RuntimeException ignored) { }
        try { child.waitFor(1500L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ex) { interrupted = true; }
        catch (RuntimeException ignored) { }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L);
        while (descendants.stream().anyMatch(BoundedProcess::aliveOrUnknown) && System.nanoTime() < deadline) {
            try { Thread.sleep(10L); }
            catch (InterruptedException ex) { interrupted = true; break; }
        }
        if (interrupted) throw new InterruptedException("Interrupted while stopping an owned child process");
        return !aliveOrUnknown(child) && descendants.stream().noneMatch(BoundedProcess::aliveOrUnknown);
    }

    private static boolean aliveOrUnknown(Process child) {
        try { return child.isAlive(); }
        catch (RuntimeException ex) { return true; }
    }

    private static boolean aliveOrUnknown(ProcessHandle handle) {
        try { return handle.isAlive(); }
        catch (RuntimeException ex) { return true; }
    }
}
