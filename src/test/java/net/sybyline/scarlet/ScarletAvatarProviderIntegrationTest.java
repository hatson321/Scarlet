package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import static net.sybyline.scarlet.ScarletDiscordReadinessTest.proxy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import net.dv8tion.jda.api.JDA;
import net.sybyline.scarlet.ext.AvatarSearch;
import net.sybyline.scarlet.ext.ExtendedUserAgent;

/** Synthetic metadata and inert application objects only; no clients or provider requests. */
class ScarletAvatarProviderIntegrationTest
{
    static final String PROVIDER = "https://provider.example.invalid/vrcx";
    static final String ACCOUNT = "usr_00000000-0000-0000-0000-000000000001";
    static final String GROUP = "grp_00000000-0000-0000-0000-000000000002";
    @TempDir Path temp;

    @BeforeEach void preventTransportOnUnexpectedCacheMiss()
    {
        AvatarSearch.providerBlockedUntil.put(PROVIDER, Long.MAX_VALUE);
    }

    @AfterEach void removeSyntheticBackoff()
    {
        AvatarSearch.providerBlockedUntil.remove(PROVIDER);
    }

    static class OfflineDiscord extends ScarletDiscordJDA
    {
        String[] providers;
        OfflineDiscord() { super(null, null, null); throw new AssertionError("No live constructor"); }
        @Override String[] getAvatarSearchProviders() { return this.providers.clone(); }
    }

    static class Fixture implements AutoCloseable
    {
        final Scarlet scarlet = inert(Scarlet.class);
        final ScarletVRChat vrc = inert(ScarletVRChat.class);
        final OfflineDiscord discord = inert(OfflineDiscord.class);
        Fixture(Path directory) throws Exception
        {
            put(scarlet, "dataDir", directory.toFile());
            put(scarlet, "running", true);
            put(scarlet, "vrc", vrc); put(scarlet, "discord", discord);
            put(vrc, "groupId", GROUP); put(vrc, "currentUserId", ACCOUNT);
            put(discord, "scarlet", scarlet);
            put(discord, "jda", proxy(JDA.class, (p, m, a) -> { throw new AssertionError("No Discord requests"); }));
            discord.providers = new String[] { PROVIDER };
        }
        @Override public void close() { discord.closeAvatarProviderCache(); }
    }

    static AvatarSearch.VrcxAvatar[] result()
    {
        AvatarSearch.VrcxAvatar avatar = new AvatarSearch.VrcxAvatar();
        avatar.id = "avtr_00000000-0000-0000-0000-000000000003";
        avatar.name = "Synthetic avatar";
        avatar.imageUrl = "https://example.invalid/image.png";
        return new AvatarSearch.VrcxAvatar[] { avatar };
    }

    static void seed(Fixture fixture, String mode, int count, String query)
    {
        assertNotNull(fixture.discord.avatarProviderCache().get(PROVIDER, mode, count, query,
            () -> true, ScarletAvatarProviderIntegrationTest::result));
    }

    @Test void productionSearchUsesTheProfileCacheAcrossRestart() throws Exception
    {
        try (Fixture first = new Fixture(temp))
        {
            seed(first, "text", AvatarSearch.HYDRATION_SEARCH_N, "Synthetic avatar");
            assertEquals(1L, first.discord.searchAvatarProviders("Synthetic avatar", AvatarSearch.HYDRATION_SEARCH_N, () -> true).count());
        }
        assertTrue(Files.size(temp.resolve("avatar-provider-cache.json")) > 0L);
        try (Fixture restarted = new Fixture(temp))
        {
            assertEquals(1L, restarted.discord.searchAvatarProviders("Synthetic avatar", AvatarSearch.HYDRATION_SEARCH_N, () -> true).count());
        }
    }

    @Test void noSelectedProviderDoesNotEvenCreateTheCache() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            fixture.discord.providers = new String[0];
            assertEquals(0L, fixture.discord.searchAvatarProviders("Synthetic", 50, () -> true).count());
            assertFalse(Files.exists(temp.resolve("avatar-provider-cache.json")));
            java.lang.reflect.Field field = ScarletDiscordJDA.class.getDeclaredField("avatarProviderCache");
            field.setAccessible(true);
            assertNull(field.get(fixture.discord));
        }
    }

    @Test void staffModeCanReuseConsentedMetadataWithoutDiscordConnection() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            put(fixture.scarlet, "staffMode", true);
            put(fixture.discord, "jda", null);
            seed(fixture, "text", 50, "Synthetic");
            assertEquals(1L, fixture.discord.searchAvatarProviders("Synthetic", 50, () -> true).count());
            fixture.discord.providers = new String[0];
            assertEquals(0L, fixture.discord.searchAvatarProviders("Synthetic", 50, () -> true).count());
        }
    }

    @Test void deniedLiveRequestDoesNotReadOrCreateDiskCache() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            assertEquals(0L, fixture.discord.searchAvatarProviders("Synthetic", 50, () -> false).count());
            assertFalse(Files.exists(temp.resolve("avatar-provider-cache.json")));
        }
    }

    @Test void permissionRevocationBeforeStreamConsumptionSuppressesCachedResults() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            seed(fixture, "text", 50, "Synthetic");
            Stream<AvatarSearch.VrcxAvatar> stream = fixture.discord.searchAvatarProviders("Synthetic", 50, () -> true);
            fixture.discord.providers = new String[0];
            assertEquals(0L, stream.count());
        }
    }

    @Test void accountSwitchBeforeStreamConsumptionSuppressesCachedResults() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            seed(fixture, "text", 50, "Synthetic");
            Stream<AvatarSearch.VrcxAvatar> stream = fixture.discord.searchAvatarProviders("Synthetic", 50, () -> true);
            put(fixture.vrc, "currentUserId", "usr_00000000-0000-0000-0000-000000000099");
            assertEquals(0L, stream.count());
        }
    }

    @Test void guardLatchesObservedContextInvalidation() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            BooleanSupplier guard = fixture.discord.avatarProviderRequestGuard(() -> true);
            assertTrue(guard.getAsBoolean());
            fixture.discord.providers = new String[0];
            assertFalse(guard.getAsBoolean());
            fixture.discord.providers = new String[] { PROVIDER };
            assertFalse(guard.getAsBoolean());
        }
    }

    @Test void groupAndPendingAuthenticationChangesInvalidateRequests() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            BooleanSupplier groupGuard = fixture.discord.avatarProviderRequestGuard(() -> true);
            put(fixture.vrc, "groupId", "grp_00000000-0000-0000-0000-000000000099");
            assertFalse(groupGuard.getAsBoolean());
            put(fixture.vrc, "groupId", GROUP);
            BooleanSupplier loginGuard = fixture.discord.avatarProviderRequestGuard(() -> true);
            put(fixture.vrc, "pendingUsername", "synthetic");
            assertFalse(loginGuard.getAsBoolean());
        }
    }

    @Test void authenticationGenerationChangeInvalidatesRequests() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            BooleanSupplier guard = fixture.discord.avatarProviderRequestGuard(() -> true);
            ExtendedUserAgent.setCurrentUserId(null);
            assertFalse(guard.getAsBoolean());
        }
    }

    @Test void stoppedRuntimeAndClosedCacheCannotPublishOrReopen() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            BooleanSupplier stopped = fixture.discord.avatarProviderRequestGuard(() -> true);
            put(fixture.scarlet, "running", false);
            assertFalse(stopped.getAsBoolean());
            put(fixture.scarlet, "running", true);
            seed(fixture, "text", 50, "Synthetic");
            Stream<AvatarSearch.VrcxAvatar> stream = fixture.discord.searchAvatarProviders("Synthetic", 50, () -> true);
            fixture.discord.closeAvatarProviderCache();
            assertEquals(0L, stream.count());
            assertNull(fixture.discord.avatarProviderCache());
            assertFalse(fixture.discord.clearAvatarProviderCache());
        }
    }

    @Test void imageFallbackCannotUseDeselectedProvider() throws Exception
    {
        try (Fixture fixture = new Fixture(temp))
        {
            assertEquals(0L, fixture.discord.searchAvatarProviderImages(
                new String[] { "https://unselected.example.invalid" }, "file_synthetic", ACCOUNT, () -> true).count());
        }
    }

    @Test void differentProfileDoesNotShareCacheObjectOrFile() throws Exception
    {
        Path other = Files.createDirectory(temp.resolve("second"));
        try (Fixture first = new Fixture(temp); Fixture second = new Fixture(other))
        {
            seed(first, "text", 50, "Synthetic");
            assertNotSame(first.discord.avatarProviderCache(), second.discord.avatarProviderCache());
            assertFalse(Files.exists(other.resolve("avatar-provider-cache.json")));
            assertTrue(first.discord.clearAvatarProviderCache());
            assertNull(first.discord.avatarProviderCache().get(PROVIDER, "text", 50, "Synthetic", () -> true, () -> null));
        }
    }
}
