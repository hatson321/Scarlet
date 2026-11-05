package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.reflect.TypeToken;
import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.model.User;
import okhttp3.*;

class ScarletFetchCoalescingTest
{
    @TempDir Path directory;
    @Test void actualUserGetterSharesAnOverlappingRequest() throws Exception
    {
        try (Fixture f = new Fixture(directory))
        {
            Future<User> first = f.pool.submit(() -> f.vrc.getUser("synthetic"));
            assertTrue(f.started.await(2,TimeUnit.SECONDS));
            AtomicReference<Thread> follower = new AtomicReference<>();
            Future<User> second = f.pool.submit(() -> { follower.set(Thread.currentThread()); return f.vrc.getUser("synthetic"); });
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(follower.get()==null || follower.get().getState()!=Thread.State.TIMED_WAITING) {
                assertTrue(System.nanoTime()<until); Thread.sleep(1);
            }
            f.release.countDown();
            assertSame(first.get(2,TimeUnit.SECONDS),second.get(2,TimeUnit.SECONDS)); assertEquals(1,f.calls.get());
            assertEquals("User 2",f.vrc.getUserFresh("synthetic").getDisplayName());
            assertEquals(2,f.calls.get());
        }
    }
    @Test void differentFreshnessRequirementsAndExplicitFreshReadsDoNotShare() throws Exception
    {
        try(Fixture f=new Fixture(directory))
        {
            Future<User> first=f.pool.submit(() -> f.vrc.getUser("synthetic",Long.MIN_VALUE));
            assertTrue(f.started.await(2,TimeUnit.SECONDS));
            assertEquals("User 2",f.vrc.getUser("synthetic",Long.MAX_VALUE).getDisplayName());
            assertEquals("User 3",f.vrc.getUserFresh("synthetic").getDisplayName());
            f.release.countDown(); assertEquals("User 1",first.get(2,TimeUnit.SECONDS).getDisplayName()); assertEquals(3,f.calls.get());
        }
    }
    static class Fixture implements AutoCloseable
    {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger(); final ExecutorService pool=Executors.newFixedThreadPool(2);
        final ScarletVRChat vrc; final OkHttpClient http;
        final com.google.gson.Gson previousJson;
        Fixture(Path directory) throws Exception
        {
            http=new OkHttpClient.Builder().dns(host -> { throw new AssertionError("Network forbidden"); }).addInterceptor(chain -> {
                assertEquals("coalescing.invalid",chain.request().url().host());
                assertNull(chain.request().header("Cookie")); assertNull(chain.request().header("Authorization"));
                int call=calls.incrementAndGet();
                if(call==1) { started.countDown(); try { assertTrue(release.await(3,TimeUnit.SECONDS)); } catch(InterruptedException ex) { throw new AssertionError(ex); } }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                    .body(ResponseBody.create(MediaType.parse("application/json"),"{\"id\":\"synthetic\",\"displayName\":\"User "+call+"\"}")).build();
            }).build();
            vrc=ScarletDiscordSafetyTest.inert(ScarletVRChat.class);
            ScarletDiscordSafetyTest.put(vrc,"client",new ApiClient(http).setBasePath("https://coalescing.invalid/api/1"));
            previousJson = io.github.vrchatapi.JSON.getGson(); ScarletVRChat.initJson();
            ScarletDiscordSafetyTest.put(vrc,"cachedUsers",new ScarletJsonCache<User>(directory.toFile(),"users",TypeToken.get(User.class)));
        }
        public void close() { release.countDown(); pool.shutdownNow(); io.github.vrchatapi.JSON.setGson(previousJson); http.connectionPool().evictAll(); http.dispatcher().executorService().shutdownNow(); }
    }
}
