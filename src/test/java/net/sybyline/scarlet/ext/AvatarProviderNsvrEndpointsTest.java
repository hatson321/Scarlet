package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** Offline endpoint and selection checks: no connections, credentials or application startup. */
class AvatarProviderNsvrEndpointsTest
{
    private static final String API_ROOT = "https://api-avatar.nekosunevr.co.uk";
    private static final String SEARCH_ROOT = "https://vrcavatarsearch.nekosunevr.co.uk";
    private static final String SEARCH_ENDPOINT = SEARCH_ROOT + "/vrcx_search";
    private static final String LEGACY_CUSTOM_ENDPOINT = "https://avtr.nekosunevr.co.uk/vrcx_search";

    @Test
    void publishedApiAndSearchRootsRemainSeparate()
    {
        assertEquals(API_ROOT, AvatarSearch_NSVR.API_ROOT);
        assertEquals(SEARCH_ROOT, AvatarSearch_NSVR.API_ROOT_SEARCH);
        assertNotEquals(AvatarSearch_NSVR.API_ROOT, AvatarSearch_NSVR.API_ROOT_SEARCH);
    }

    @Test
    void legacyEntryPointAliasesTheNewRoots()
    {
        assertEquals(AvatarSearch_NSVR.API_ROOT, AvatarSearch_VRCDS.API_ROOT);
        assertEquals(AvatarSearch_NSVR.API_ROOT_SEARCH, AvatarSearch_VRCDS.SEARCH_ROOT);
    }

    @Test
    void newPublicTypeCompilesUnderItsMatchingJavaName()
    {
        assertEquals("net.sybyline.scarlet.ext.AvatarSearch_NSVR", AvatarSearch_NSVR.class.getName());
        assertEquals("AvatarSearch_NSVR", AvatarSearch_NSVR.class.getSimpleName());
        assertTrue(AvatarSearch_NSVR.class.isInterface());
        assertTrue(Modifier.isPublic(AvatarSearch_NSVR.class.getModifiers()));
        assertTrue(Modifier.isPublic(AvatarSearch_VRCDS.class.getModifiers()));
    }

    @Test
    void activeSearchUsesTheSearchHostAndAddsTheRouteOnce()
    {
        assertEquals(SEARCH_ENDPOINT, AvatarSearch.URL_ROOT_NEKOSUNEVR);
        assertFalse(AvatarSearch.URL_ROOT_NEKOSUNEVR.startsWith(API_ROOT + "/"));
        assertFalse(AvatarSearch.URL_ROOT_NEKOSUNEVR.contains("/vrcx_search/vrcx_search"));
    }

    @Test
    void defaultNameAndAuthorSelectionsIncludeTheProviderExactlyOnce()
    {
        assertEquals(1L, Arrays.stream(AvatarSearch.URL_ROOTS).filter(SEARCH_ENDPOINT::equals).count());
        assertEquals(1L, Arrays.stream(AvatarSearch.ByImage.AUTHOR_LOOKUP_URL_ROOTS)
            .filter(SEARCH_ENDPOINT::equals).count());
        assertArrayEquals(AvatarSearch.URL_ROOTS,
            AvatarSearch.effectiveProviders(true, true, false, null, null));
    }

    @Test
    void domainChangeDoesNotAdvertiseUnsupportedDirectImageSearch()
    {
        assertFalse(Arrays.asList(AvatarSearch.ByImage.BY_IMAGE_URL_ROOTS).contains(SEARCH_ENDPOINT));
        assertArrayEquals(new String[0], AvatarSearch.ByImage.selectedImageProviders(new String[]{SEARCH_ENDPOINT}));
    }

    @Test
    void newProviderStillRequiresExplicitConsentAndEnabledFeature()
    {
        assertArrayEquals(new String[0], AvatarSearch.effectiveProviders(true, null, false, null, null));
        assertArrayEquals(new String[0], AvatarSearch.effectiveProviders(true, false, false, null, null));
        assertArrayEquals(new String[0], AvatarSearch.effectiveProviders(false, true, false, null, null));
        assertArrayEquals(new String[0], AvatarSearch.effectiveProviders(true, false, true,
            new String[]{SEARCH_ENDPOINT}, null));
    }

    @Test
    void emptyOrMissingCustomSelectionNeverFallsBackToNewDefaults()
    {
        assertArrayEquals(new String[0], AvatarSearch.effectiveProviders(true, true, true, null, null));
        assertArrayEquals(new String[0], AvatarSearch.effectiveProviders(true, true, true, new String[0], null));
    }

    @Test
    void blockedHostRemainsExcludedFromDefaultAndCustomSelections()
    {
        String blockedHost = "vrcavatarsearch.nekosunevr.co.uk";
        String[] defaults = AvatarSearch.effectiveProviders(true, true, false, null, blockedHost);
        assertFalse(Arrays.asList(defaults).contains(SEARCH_ENDPOINT));
        assertTrue(Arrays.asList(defaults).contains(AvatarSearch.URL_ROOT_VRCDB));
        assertArrayEquals(new String[]{"https://selected.invalid/search"},
            AvatarSearch.effectiveProviders(true, true, true,
                new String[]{SEARCH_ENDPOINT, "https://selected.invalid/search"}, blockedHost));
    }

    @Test
    void savedLegacyCustomEndpointIsNotSilentlyRewritten()
    {
        String[] configured = {LEGACY_CUSTOM_ENDPOINT, "https://selected.invalid/search"};
        String[] selected = AvatarSearch.effectiveProviders(true, true, true, configured, null);
        assertArrayEquals(configured, selected);
        assertEquals(LEGACY_CUSTOM_ENDPOINT, configured[0]);
        assertFalse(Arrays.asList(selected).contains(SEARCH_ENDPOINT));
    }

    @Test
    void explicitCustomSelectionDoesNotEnableOtherDefaultProviders()
    {
        assertArrayEquals(new String[]{SEARCH_ENDPOINT}, AvatarSearch.effectiveProviders(true, true, true,
            new String[]{SEARCH_ENDPOINT, SEARCH_ENDPOINT}, null));
        assertArrayEquals(new String[]{"https://selected.invalid/search"},
            AvatarSearch.effectiveProviders(true, true, true,
                new String[]{"https://selected.invalid/search"}, null));
    }
}
