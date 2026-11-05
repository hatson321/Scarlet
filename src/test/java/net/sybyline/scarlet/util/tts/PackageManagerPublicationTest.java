package net.sybyline.scarlet.util.tts;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PackageManagerPublicationTest {
    @Test void concurrentDetectionPublishesOnlyCompleteImmutableResults() throws Exception {
        LinuxPackageManagerDetector.clearCache();
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->LinuxPackageManagerDetector.detectAllPackageManagers(command->{
                entered.countDown(); try { if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("timeout"); }
                catch(InterruptedException ex){throw new AssertionError(ex);} return true;
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            var second=workers.submit(()->LinuxPackageManagerDetector.detectAllPackageManagers(command->{throw new AssertionError("duplicate detection");}));
            assertFalse(second.isDone()); release.countDown();
            var result=first.get(3,TimeUnit.SECONDS);
            assertTrue(result.size()>5); assertSame(result,second.get(3,TimeUnit.SECONDS));
            assertThrows(UnsupportedOperationException.class,result::clear);
            assertSame(result.get(0),LinuxPackageManagerDetector.getPrimaryPackageManager());
        } finally {release.countDown();workers.shutdownNow();LinuxPackageManagerDetector.clearCache();}
    }
}
