package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.sybyline.scarlet.Scarlet;
import net.sybyline.scarlet.ext.AvatarSearch.VrcxAvatar;

class AvatarProviderPolicyTest
{
    @AfterEach
    void clearSyntheticCaches()
    {
        VrcxAvatar.searchCacheByUrlRoot.clear();
        VrcxAvatar.searchCacheByUrlRootByImage.clear();
        AvatarSearch.clearProviderBackoffs();
        ExtendedUserAgent.setCurrentGroupId(null);
        ExtendedUserAgent.setCurrentUserId(null);
    }

    @Test
    void providerAccessRequiresExplicitConsentAndEnabledFeature()
    {
        assertEquals(0, AvatarSearch.effectiveProviders(true, null, false, null, null).length);
        assertEquals(0, AvatarSearch.effectiveProviders(true, false, false, null, null).length);
        assertEquals(0, AvatarSearch.effectiveProviders(false, true, false, null, null).length);
        assertEquals(0, AvatarSearch.effectiveProviders(true, true, true, null, null).length);
        assertEquals(0, AvatarSearch.effectiveProviders(true, true, true, new String[0], null).length);
        assertArrayEquals(AvatarSearch.URL_ROOTS, AvatarSearch.effectiveProviders(true, true, false, null, null));
    }

    @Test
    void effectiveSelectionTrimsDeduplicatesAndHonorsBlockedHost()
    {
        assertArrayEquals(new String[] {"https://chosen.invalid/search"},
            AvatarSearch.effectiveProviders(true, true, true,
                new String[] {null, "", " https://chosen.invalid/search ", "https://chosen.invalid/search", "https://BLOCKED.invalid/search"},
                "blocked.invalid"));
    }

    private static VrcxAvatar avatar(String id)
    {
        VrcxAvatar result = new VrcxAvatar();
        result.id = id;
        result.imageUrl = "https://image.invalid/file_synthetic/image.png";
        return result;
    }

    private static void seed(String provider, String id)
    {
        VrcxAvatar[] result = {avatar(id)};
        VrcxAvatar.searchCacheByUrlRoot.put(provider, new HashMap<>(Collections.singletonMap("synthetic-name", result)));
        VrcxAvatar.searchCacheByUrlRootByImage.put(AvatarSearch.ByImage.authorStateKey(provider),
            new HashMap<>(Collections.singletonMap("synthetic-author", result)));
    }

    @Test
    void deselectingAllProvidersDisablesBothNameAndImageAuthorPaths()
    {
        for (String provider : AvatarSearch.URL_ROOTS) seed(provider, "never-used");
        String[] none = AvatarSearch.effectiveProviders(true, true, true, new String[0], null);
        assertEquals(0, AvatarSearch.vrcxSearchAllCached(none, "synthetic-name").count());
        assertEquals(0, AvatarSearch.ByImage.vrcxSearchAllCachedByImage(none, "file_synthetic", "synthetic-author").count());
        assertEquals(0, AvatarSearch.ByImage.vrcxSearchAllByImage(none, "file_synthetic", "synthetic-author").count());
    }

    @Test
    void imageAuthorFallbackUsesOnlySelectedBuiltInAndCustomProviders()
    {
        for (String provider : AvatarSearch.URL_ROOTS) seed(provider, "excluded");
        String chosen = AvatarSearch.URL_ROOT_NEKOSUNEVR;
        String custom = "https://custom.invalid/search";
        seed(chosen, "chosen");
        seed(custom, "custom");
        assertEquals(Arrays.asList("chosen", "custom"),
            AvatarSearch.ByImage.vrcxSearchAllCachedByImage(new String[] {chosen, custom}, "file_synthetic", "synthetic-author")
                .map(VrcxAvatar::id).collect(Collectors.toList()));
        assertEquals(Collections.singletonList("chosen"), AvatarSearch.vrcxSearchAllCached(new String[] {chosen}, "synthetic-name")
            .map(VrcxAvatar::id).collect(Collectors.toList()));
    }

    @Test
    void knownNameOnlyProviderIsNotQueriedForAuthorMatching()
    {
        seed(AvatarSearch.URL_ROOT_AVTRDB, "name-only");
        assertEquals(0, AvatarSearch.ByImage.vrcxSearchAllCachedByImage(new String[] {AvatarSearch.URL_ROOT_AVTRDB},
            "file_synthetic", "synthetic-author").count());
    }

    @Test
    void unrelatedRequestHeaderNeverIncludesGroupOrOperatorIdentity() throws Exception
    {
        String groupId = "grp_11111111-1111-4111-8111-111111111111";
        String userId = "usr_22222222-2222-4222-8222-222222222222";
        ExtendedUserAgent.setCurrentGroupId(groupId);
        ExtendedUserAgent.setCurrentUserId(userId);
        HttpURLConnection connection = new HttpURLConnection(new URL("https://provider.invalid")) {
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public void connect() { fail("This test must never open a connection"); }
        };
        ExtendedUserAgent.init_conn.invoke(connection);
        assertEquals(Scarlet.USER_AGENT, connection.getRequestProperty("User-Agent"));
        assertFalse(connection.getRequestProperty("User-Agent").contains(groupId));
        assertFalse(connection.getRequestProperty("User-Agent").contains(userId));
    }

    @Test
    void everyActiveProviderModeRejectsNonPublicDestinationsBeforeConnecting()
    {
        IOException blocked = assertThrows(IOException.class, () -> AvatarSearch.openProvider("http://127.0.0.1/search"));
        assertTrue(blocked.getMessage().contains("Blocked unsafe resolved address"));
        assertNull(AvatarSearch.vrcxSearch0("http://127.0.0.1/name", 10, "synthetic"));
        assertNull(AvatarSearch.ByImage.vrcxSearch0ByImage("http://127.0.0.1/image", 10, "synthetic-file"));
        assertNull(AvatarSearch.ByImage.vrcxSearch0ByAuthor("http://127.0.0.1/author", 10, "synthetic-author"));
    }
}
