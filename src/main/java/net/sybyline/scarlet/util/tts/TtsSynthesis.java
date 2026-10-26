package net.sybyline.scarlet.util.tts;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Bounded speech generation and recovery, independent of audio playback. */
final class TtsSynthesis implements AutoCloseable
{
    private final TtsProvider provider;
    private final long timeoutMillis;
    private final Consumer<String> diagnostics;
    private final BiConsumer<String, String> recovered;
    private final ThreadPoolExecutor worker;
    private volatile CompletableFuture<Path> active;
    // A timed-out native call may still be running. Never submit more work behind it,
    // spawn replacement native workers, or assume Future.cancel stops native code.
    private volatile CompletableFuture<Path> outstanding;
    private volatile boolean closed;

    TtsSynthesis(TtsProvider provider, long timeoutMillis, Consumer<String> diagnostics,
        BiConsumer<String, String> recovered)
    {
        this.provider = provider;
        this.timeoutMillis = timeoutMillis;
        this.diagnostics = diagnostics;
        this.recovered = recovered;
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), r -> {
                Thread thread = new Thread(r, "Scarlet-speech-generation");
                thread.setDaemon(true);
                return thread;
            });
    }

    CompletableFuture<Path> submit(String text, String selected)
    {
        CompletableFuture<Path> result = new CompletableFuture<>();
        try { this.worker.execute(() -> this.generate(text, selected, result)); }
        catch (RejectedExecutionException ex)
        {
            this.diagnostics.accept(this.closed ? "speech-service-closed" : "speech-queue-full-announcement-dropped");
            result.complete(null);
        }
        return result;
    }

    private void generate(String text, String selected, CompletableFuture<Path> result)
    {
        this.active = result;
        try
        {
            if (this.closed || result.isCancelled()) return;
            if (this.outstanding != null && !this.outstanding.isDone())
            {
                this.diagnostics.accept("speech-provider-still-busy-no-new-native-request");
                return;
            }
            Set<String> candidates = new LinkedHashSet<>();
            candidates.add(selected);
            candidates.addAll(this.provider.voices());
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.timeoutMillis);
            int attempts = 0;
            for (String voice : candidates)
            {
                if (this.closed || result.isCancelled() || ++attempts > 8 || System.nanoTime() - deadline >= 0) break;
                try
                {
                    this.outstanding = this.provider.speak(text, voice, 1.0F, 1.0F);
                    if (this.outstanding == null) continue;
                    Path path = null;
                    while (!this.closed && !result.isCancelled())
                    {
                        long left = deadline - System.nanoTime();
                        if (left <= 0L)
                        {
                            this.diagnostics.accept("speech-generation-timed-out-check-voice-or-restart-Scarlet");
                            return;
                        }
                        try { path = this.outstanding.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); break; }
                        catch (TimeoutException waiting) { }
                    }
                    if (this.closed || result.isCancelled()) return;
                    if (path != null)
                    {
                        if (!java.util.Objects.equals(selected, voice)) this.recovered.accept(selected, voice);
                        result.complete(path);
                        return;
                    }
                    this.diagnostics.accept("speech-voice-returned-no-audio-trying-next");
                }
                catch (ExecutionException | RuntimeException ex)
                {
                    this.diagnostics.accept("speech-voice-failed-trying-next");
                }
            }
            this.diagnostics.accept("speech-no-working-voice-check-installed-voices");
        }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        finally { result.complete(null); this.active = null; }
    }

    boolean skip()
    {
        CompletableFuture<Path> result = this.active;
        return result != null && result.cancel(false);
    }

    public void close()
    {
        this.closed = true;
        this.skip();
        // Drain cancelled/queued tasks so every caller receives a completion.
        this.worker.shutdown();
    }
}
