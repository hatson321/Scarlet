package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import net.sybyline.scarlet.util.Location;
import net.sybyline.scarlet.util.VrcIds;

/** All IDs and URLs are synthetic; parsing never resolves a link or calls VRChat. */
class ScarletCloseInstanceInputTest
{
    static final String WORLD = "wrld_11111111-2222-4333-8444-555555555555";
    static final String GROUP = "grp_aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    static final String OTHER_GROUP = "grp_99999999-8888-4777-8666-555555555555";
    static final String INSTANCE = "12345~group(" + GROUP + ")~groupAccessType(members)~ageGate~region(us)";
    static final String RAW = WORLD + ":" + INSTANCE;
    static final String URL = "https://vrchat.com/home/launch?worldId=" + WORLD + "&instanceId=" + INSTANCE;

    @Test void ageGatedFullLaunchLinkPreservesEveryInstanceSuffix()
    {
        assertFalse(VrcIds.url_location.matcher(URL).matches(), "Fixture must reproduce the legacy standalone-flag mismatch");
        Location parsed = ScarletCloseInstanceInput.parse(URL, GROUP);
        assertEquals(WORLD, parsed.world);
        assertEquals(INSTANCE, parsed.instance);
        assertEquals(GROUP, parsed.owner);
        assertTrue(parsed.ageGate);
        assertEquals(Location.Type.GROUP, parsed.type);
    }

    @Test void encodedQueryValuesAndReorderedParametersAreAccepted() throws Exception
    {
        String encoded = URLEncoder.encode(INSTANCE, StandardCharsets.UTF_8.name());
        String url = "https://vrchat.com/home/launch?instanceId=" + encoded + "&shortName=abcdefgh&worldId=" + WORLD;
        assertEquals(INSTANCE, ScarletCloseInstanceInput.parse(url, GROUP).instance);
    }

    @Test void canonicalAndVrcxLocationsAreAccepted()
    {
        assertEquals(INSTANCE, ScarletCloseInstanceInput.parse(RAW, GROUP).instance);
        assertEquals(INSTANCE, ScarletCloseInstanceInput.parse("vrcx://world/" + RAW, GROUP).instance);
    }

    @Test void DiscordAngleOrCodeWrappersAndSurroundingWhitespaceAreAccepted()
    {
        assertEquals(INSTANCE, ScarletCloseInstanceInput.parse("  <" + URL + ">  ", GROUP).instance);
        assertEquals(INSTANCE, ScarletCloseInstanceInput.parse("`" + RAW + "`", GROUP).instance);
    }

    @Test void groupPublicAndGroupPlusTargetsAreAccepted()
    {
        assertEquals(Location.Type.GROUP_PUBLIC,
            ScarletCloseInstanceInput.parse(RAW.replace("groupAccessType(members)", "groupAccessType(public)"), GROUP).type);
        assertEquals(Location.Type.GROUP_PLUS,
            ScarletCloseInstanceInput.parse(RAW.replace("groupAccessType(members)", "groupAccessType(plus)"), GROUP).type);
    }

    static Stream<String> invalidInputs()
    {
        return Stream.of(null, "", WORLD, WORLD + ":", "offline", "private", "abcdefgh",
            "https://vrch.at/abcdefgh", "https://vrchat.com/i/abcdefgh",
            "https://vrchat.com/home/world/" + WORLD,
            "https://vrchat.com/home/launch?worldId=" + WORLD,
            "https://vrchat.com/home/launch?instanceId=" + INSTANCE,
            URL + "&worldId=" + WORLD, URL + "&instanceId=" + INSTANCE,
            URL.replace("~ageGate", "~ageGate~ageGate"),
            URL.replace("~ageGate", "~group(" + OTHER_GROUP + ")~ageGate"),
            URL.replace("~groupAccessType(members)", "~group(" + GROUP + ")~groupAccessType(members)"),
            URL.replace("~ageGate", "~groupAccessType(public)~ageGate"),
            URL.replace("~region(us)", "~region(us"),
            URL.replace("instanceId=", "instanceId=%ZZ"),
            URL.replace("worldId=", "worldId=%253A"),
            URL + "#ignored", URL + "&", RAW + " garbage", RAW + ":extra",
            URL.replace("https://vrchat.com", "http://vrchat.com"),
            URL.replace("https://vrchat.com", "https://vrchat.com.example.invalid"),
            URL.replace("https://vrchat.com", "https://example.invalid/vrchat.com"),
            URL.replace("https://vrchat.com", "https://user@vrchat.com"),
            URL.replace("https://vrchat.com", "https://vrchat.com:8443"),
            URL.replace("/home/launch", "/home/other"));
    }

    @ParameterizedTest @MethodSource("invalidInputs")
    void incompleteMalformedOrUntrustedInputGetsAnActionableValidationError(String input)
    {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ScarletCloseInstanceInput.parse(input, GROUP));
        assertEquals(ScarletCloseInstanceInput.INVALID, failure.getMessage());
    }

    @Test void anotherGroupIsRejectedBeforeAnyCloseRequest()
    {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ScarletCloseInstanceInput.parse(URL, OTHER_GROUP));
        assertTrue(failure.getMessage().contains("configured VRChat group"));
    }

    @Test void missingConfiguredGroupIsRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> ScarletCloseInstanceInput.parse(URL, null));
        assertThrows(IllegalArgumentException.class, () -> ScarletCloseInstanceInput.parse(URL, "invalid"));
    }

    @Test void nonGroupInstancesAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> ScarletCloseInstanceInput.parse(WORLD + ":12345", GROUP));
    }
}
