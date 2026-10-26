package net.sybyline.scarlet.util.tts;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Fake voice futures only; no audio hardware, native calls or user data. */
class TtsSynthesisTest
{
    static class Provider implements TtsProvider
    {
        final List<String> called = new CopyOnWriteArrayList<>();
        final Map<String, CompletableFuture<Path>> responses = new HashMap<>();
        public List<String> voices() { return Arrays.asList("A", "B", "C"); }
        public CompletableFuture<Path> speak(String text, String voice, float volume, float speed)
        { called.add(voice); return responses.getOrDefault(voice, CompletableFuture.completedFuture(null)); }
        public void close() { }
    }

    @Test void nullAndExceptionalVoicesReachThirdVoiceAndSaveOnlySuccess() throws Exception
    {
        Provider p = new Provider();
        CompletableFuture<Path> failed = new CompletableFuture<>(); failed.completeExceptionally(new IllegalStateException("fake"));
        p.responses.put("B", failed); p.responses.put("C", CompletableFuture.completedFuture(Paths.get("synthetic.wav")));
        List<String> recovered = new CopyOnWriteArrayList<>();
        try (TtsSynthesis s = new TtsSynthesis(p, 1000, reason -> {}, (old, voice) -> recovered.add(voice)))
        {
            assertEquals(Paths.get("synthetic.wav"), s.submit("test", "A").get(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("A", "B", "C"), p.called);
            assertEquals(Collections.singletonList("C"), recovered);
        }
    }

    @Test void cancelledVoiceFallsBackButSkipDoesNotSelectAnotherVoice() throws Exception
    {
        Provider p = new Provider(); CompletableFuture<Path> cancelled = new CompletableFuture<>(); cancelled.cancel(false);
        p.responses.put("A", cancelled); p.responses.put("B", CompletableFuture.completedFuture(Paths.get("ok.wav")));
        try (TtsSynthesis s = new TtsSynthesis(p, 1000, r -> {}, (a, b) -> {}))
        { assertNotNull(s.submit("test", "A").get(2, TimeUnit.SECONDS)); }
        p.called.clear(); p.responses.put("A", new CompletableFuture<>());
        try (TtsSynthesis s = new TtsSynthesis(p, 5000, r -> {}, (a, b) -> fail("Skip must not change voice")))
        {
            CompletableFuture<Path> result = s.submit("test", "A");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (p.called.isEmpty() && System.nanoTime() < until) Thread.yield();
            assertFalse(p.called.isEmpty()); assertTrue(s.skip()); assertTrue(result.isCancelled());
            assertNull(s.submit("next", "A").get(2, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("A"), p.called);
        }
    }

    @Test void timeoutUnblocksQueueWithoutAccumulatingNativeRequestsAndRecoversAfterCompletion() throws Exception
    {
        Provider p = new Provider(); CompletableFuture<Path> stuck = new CompletableFuture<>(); p.responses.put("A", stuck);
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (TtsSynthesis s = new TtsSynthesis(p, 100, diagnostics::add, (a,b) -> {}))
        {
            assertNull(s.submit("first", "A").get(2, TimeUnit.SECONDS));
            assertNull(s.submit("second", "A").get(2, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("A"), p.called);
            assertTrue(diagnostics.stream().anyMatch(r -> r.contains("timed-out")));
            assertTrue(diagnostics.stream().anyMatch(r -> r.contains("still-busy")));
            stuck.complete(Paths.get("late.wav"));
            assertEquals(Paths.get("late.wav"), s.submit("third", "A").get(2, TimeUnit.SECONDS));
        }
    }

    @Test void queueIsBoundedAndCloseCompletesAllWaitingCallers() throws Exception
    {
        Provider p = new Provider(); p.responses.put("A", new CompletableFuture<>());
        AtomicInteger drops = new AtomicInteger();
        TtsSynthesis s = new TtsSynthesis(p, 5000, r -> { if(r.contains("queue-full")) drops.incrementAndGet(); }, (a,b) -> {});
        List<CompletableFuture<Path>> results = new ArrayList<>();
        for (int i=0;i<100;i++) results.add(s.submit("synthetic", "A"));
        assertTrue(drops.get()>0); s.close();
        for (CompletableFuture<Path> result : results)
            try { result.get(2, TimeUnit.SECONDS); } catch (CancellationException expected) { }
        assertNull(s.submit("after-close", "A").get(2, TimeUnit.SECONDS));
    }
}
