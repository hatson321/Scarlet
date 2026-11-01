package net.sybyline.scarlet;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import net.sybyline.scarlet.util.VrcRateLimiter;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Shared audit pacing; only explicitly scoped historical reads may retry a 429. */
final class ScarletAuditRequestPolicy implements Interceptor
{
    static final long MIN_BACKOFF_MS = 30_000L;
    static final long HISTORY_BUDGET_MS = 240_000L;
    static final int HISTORY_RETRIES = 2;
    private final VrcRateLimiter global;
    private final VrcRateLimiter audits;
    private final LongConsumer onPenalty;
    private final LongSupplier clock;
    private final long historyBudgetMs;
    private final ThreadLocal<History> history = new ThreadLocal<>();
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlineExecutor();

    private static ScheduledThreadPoolExecutor deadlineExecutor()
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "Scarlet-Audit-Read-Deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    ScarletAuditRequestPolicy(VrcRateLimiter global, LongConsumer onPenalty)
    {
        this(global, new VrcRateLimiter(0.5D, 1D), onPenalty, System::currentTimeMillis);
    }

    ScarletAuditRequestPolicy(VrcRateLimiter global, VrcRateLimiter audits,
            LongConsumer onPenalty, LongSupplier clock)
    {
        this(global, audits, onPenalty, clock, HISTORY_BUDGET_MS);
    }

    ScarletAuditRequestPolicy(VrcRateLimiter global, VrcRateLimiter audits,
            LongConsumer onPenalty, LongSupplier clock, long historyBudgetMs)
    {
        this.global = global;
        this.audits = audits;
        this.onPenalty = onPenalty;
        this.clock = clock;
        this.historyBudgetMs = Math.max(1L, historyBudgetMs);
    }

    final class History implements AutoCloseable
    {
        private final History previous;
        private final long deadline;
        private final ScheduledFuture<?> timeoutTask;
        private int retries = HISTORY_RETRIES;
        private boolean timedOut, closed;
        private okhttp3.Call activeCall;

        private History()
        {
            this.previous = history.get();
            this.deadline = this.previous == null ? addSaturated(clock.getAsLong(), historyBudgetMs) : this.previous.deadline;
            // This watchdog is armed before execute() and stays active while the
            // SDK reads/parses response bodies, not merely during interception.
            this.timeoutTask = this.previous == null
                ? DEADLINES.schedule(this::expire, historyBudgetMs, TimeUnit.MILLISECONDS) : null;
        }

        private History budget()
        {
            return this.previous == null ? this : this.previous.budget();
        }

        private synchronized void expire()
        {
            if (this.closed) return;
            this.timedOut = true;
            if (this.activeCall != null) this.activeCall.cancel();
        }

        private synchronized void register(okhttp3.Call call) throws InterruptedIOException
        {
            if (this.closed || this.timedOut || clock.getAsLong() >= this.deadline)
            {
                call.cancel();
                throw new InterruptedIOException("Historical audit query time budget exhausted; no complete result");
            }
            this.activeCall = call;
        }

        boolean expired()
        {
            History root = this.budget();
            synchronized (root) { return root.timedOut || clock.getAsLong() >= root.deadline; }
        }

        @Override public synchronized void close()
        {
            this.closed = true;
            this.activeCall = null;
            if (this.timeoutTask != null) this.timeoutTask.cancel(false);
            if (this.previous == null) history.remove();
            else history.set(this.previous);
        }
    }

    History historicalRead()
    {
        History scope = new History();
        this.history.set(scope);
        return scope;
    }

    static boolean isAuditRead(Request request)
    {
        if (!"GET".equals(request.method())) return false;
        List<String> path = request.url().pathSegments();
        int size = path.size();
        return size >= 3 && "groups".equals(path.get(size - 3))
            && !path.get(size - 2).isEmpty() && "auditLogs".equals(path.get(size - 1));
    }

    @Override public Response intercept(Chain chain) throws IOException
    {
        boolean audit = isAuditRead(chain.request());
        History scope = audit ? this.history.get() : null;
        History budget = scope == null ? null : scope.budget();
        long deadline = budget == null ? Long.MAX_VALUE : budget.deadline;
        if (budget != null) budget.register(chain.call());
        while (true)
        {
            checkActive(chain, deadline);
            this.global.acquireUntil(deadline, chain.call()::isCanceled);
            if (audit) this.audits.acquireUntil(deadline, chain.call()::isCanceled);
            checkActive(chain, deadline);
            // A penalty may have arrived while the audit pacer was waiting.
            // Reacquire both gates, with audit pacing last, so queued callers do
            // not discharge their old permits together after the cooldown.
            if (this.global.isBackingOff()) continue;
            Chain requestChain = chain;
            if (budget != null)
            {
                long remaining = Math.max(1L, deadline - this.clock.getAsLong());
                int requestTimeout = (int) Math.min(20_000L, remaining);
                requestChain = chain
                    .withConnectTimeout(timeoutAtMost(chain.connectTimeoutMillis(), requestTimeout), TimeUnit.MILLISECONDS)
                    .withReadTimeout(timeoutAtMost(chain.readTimeoutMillis(), requestTimeout), TimeUnit.MILLISECONDS);
            }
            Response response = requestChain.proceed(chain.request());
            if (response.code() != 429) return response;
            long pause = this.global.penalize(retryAfterMillis(response, MIN_BACKOFF_MS, this.clock.getAsLong()));
            this.onPenalty.accept(pause);
            // Never retry writes, routine polling, other failures, or beyond the
            // shared retry/time budget for this historical collection.
            if (budget == null || budget.retries <= 0
                    || pause >= Math.max(0L, deadline - this.clock.getAsLong())) return response;
            budget.retries--;
            response.close();
            // Same request/offset is retried. Previously fetched pages are kept
            // by auditQuery until the full collection succeeds or fails.
        }
    }

    private void checkActive(Chain chain, long deadline) throws IOException
    {
        ScarletVRChat.checkSnapshotRequest(chain);
        if (Thread.currentThread().isInterrupted() || chain.call().isCanceled())
            throw new InterruptedIOException("VRChat request cancelled before dispatch");
        if (this.clock.getAsLong() >= deadline)
            throw new InterruptedIOException("Historical audit query time budget exhausted; no complete result");
    }

    private static int timeoutAtMost(int current, int ceiling)
    {
        return current <= 0 ? ceiling : Math.min(current, ceiling);
    }

    static long retryAfterMillis(Response response, long defaultMs, long now)
    {
        long fallback = Math.max(MIN_BACKOFF_MS, defaultMs);
        String header = response.header("Retry-After");
        if (header == null || header.trim().isEmpty()) return fallback;
        header = header.trim();
        try
        {
            long seconds = Long.parseLong(header);
            if (seconds <= 0L) return fallback;
            return Math.max(MIN_BACKOFF_MS, seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L);
        }
        catch (NumberFormatException ignored)
        {
            // A very large positive integer is still a server cooldown, not an
            // excuse to fall back to a shorter wait and send another request.
            if (header.matches("[0-9]+")) return Long.MAX_VALUE;
            try
            {
                long until = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return until <= now ? fallback : Math.max(MIN_BACKOFF_MS, until - now);
            }
            catch (Exception invalid) { return fallback; }
        }
    }

    private static long addSaturated(long start, long duration)
    {
        return start > Long.MAX_VALUE - duration ? Long.MAX_VALUE : start + duration;
    }
}
