package net.sybyline.scarlet.util;

import java.util.concurrent.*;

public final class BoundedInitialization {
    private BoundedInitialization() { }
    public static void run(ExecutorService executor, Runnable initialize, long timeoutMillis)
            throws InterruptedException, ExecutionException {
        Future<?> future = executor.submit(initialize);
        try { future.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (TimeoutException ex) {
            future.cancel(true); executor.shutdownNow();
            throw new ExecutionException("Initialization timed out", ex);
        } catch (InterruptedException | ExecutionException ex) {
            future.cancel(true); executor.shutdownNow(); throw ex;
        }
    }
}
