package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.net.URL;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.sybyline.scarlet.Scarlet;

class AvatarProviderUserAgentTest
{
    private static final String GROUP_ONE = "grp_11111111-1111-4111-8111-111111111111";
    private static final String GROUP_TWO = "grp_22222222-2222-4222-8222-222222222222";
    private static final String OPERATOR_ONE = "usr_33333333-3333-4333-8333-333333333333";
    private static final String OPERATOR_TWO = "usr_44444444-4444-4444-8444-444444444444";
    private static final String QUERY_TARGET = "usr_55555555-5555-4555-8555-555555555555";
    private static final String BASE = "Hatson321-Scarlet/" + Scarlet.VERSION
        + " Discord:@hatson321/hatson321#0029 298959676094021632;"
        + " https://discord.gg/CP3AyhypBF; https://github.com/Hatson321/Scarlet";

    @BeforeEach
    @AfterEach
    void clearCurrentIdentity()
    {
        ExtendedUserAgent.setCurrentGroupId(null);
        ExtendedUserAgent.setCurrentUserId(null);
    }

    private static HttpURLConnection connection() throws Exception
    {
        return new HttpURLConnection(new URL("https://avatar-provider.invalid/search")) {
            @Override
            public void disconnect() { }

            @Override
            public boolean usingProxy() { return false; }

            @Override
            public void connect()
            {
                fail("User-Agent tests must not connect to an avatar provider");
            }
        };
    }

    private static String avatarHeader() throws Exception
    {
        HttpURLConnection connection = connection();
        ExtendedUserAgent.init_avatar_provider_conn.invoke(connection);
        return connection.getRequestProperty("User-Agent");
    }

    @Test
    void avatarHeaderUsesExactVersionContactAndCurrentIdentity() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);

        String expected = BASE + "; Group/" + GROUP_ONE + "; Operator/" + OPERATOR_ONE;
        assertEquals(expected, ExtendedUserAgent.getAvatarProviderUserAgent());
        assertEquals(expected, avatarHeader());
        assertFalse(expected.contains("unknown"));
        assertFalse(expected.contains("\r"));
        assertFalse(expected.contains("\n"));
    }

    @Test
    void missingIdentityLeavesOnlyTheCompleteBaseHeader() throws Exception
    {
        assertEquals(BASE, ExtendedUserAgent.getAvatarProviderUserAgent());
        assertEquals(BASE, avatarHeader());
    }

    @Test
    void groupAndOperatorCanEachBePresentIndependently() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        assertEquals(BASE + "; Group/" + GROUP_ONE, avatarHeader());

        ExtendedUserAgent.setCurrentGroupId(null);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        assertEquals(BASE + "; Operator/" + OPERATOR_ONE, avatarHeader());
    }

    @Test
    void eachRequestUsesTheLatestGroupAndSignedInOperator() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        HttpURLConnection first = connection();
        ExtendedUserAgent.init_avatar_provider_conn.invoke(first);

        ExtendedUserAgent.setCurrentGroupId(GROUP_TWO);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_TWO);
        HttpURLConnection second = connection();
        ExtendedUserAgent.init_avatar_provider_conn.invoke(second);

        assertEquals(BASE + "; Group/" + GROUP_ONE + "; Operator/" + OPERATOR_ONE,
            first.getRequestProperty("User-Agent"));
        assertEquals(BASE + "; Group/" + GROUP_TWO + "; Operator/" + OPERATOR_TWO,
            second.getRequestProperty("User-Agent"));
    }

    @Test
    void clearingOneIdentityDoesNotRetainItOrClearTheOther() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        ExtendedUserAgent.setCurrentUserId(null);
        assertEquals(BASE + "; Group/" + GROUP_ONE, avatarHeader());

        ExtendedUserAgent.setCurrentUserId(OPERATOR_TWO);
        ExtendedUserAgent.setCurrentGroupId(null);
        assertEquals(BASE + "; Operator/" + OPERATOR_TWO, avatarHeader());

        ExtendedUserAgent.setCurrentUserId(null);
        assertEquals(BASE, avatarHeader());
    }

    @Test
    void malformedGroupIdsClearPreviousGroupAndCannotInjectHeaders() throws Exception
    {
        String[] invalid = {
            "", "unknown", "grp_synthetic", OPERATOR_ONE,
            "grp_1-1-1-1-1", "grp_11111111111141118111111111111111",
            "grp_gggggggg-1111-4111-8111-111111111111",
            " " + GROUP_ONE, GROUP_ONE + " ", GROUP_ONE + "; Operator/" + QUERY_TARGET,
            GROUP_ONE + "\r\nX-Injected: yes", GROUP_ONE + "\n", GROUP_ONE + "\r",
            GROUP_ONE + "\t", GROUP_ONE + "\u0000"
        };
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        for (int i = 0; i < invalid.length; i++)
        {
            ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
            ExtendedUserAgent.setCurrentGroupId(invalid[i]);
            assertEquals(BASE + "; Operator/" + OPERATOR_ONE, avatarHeader(),
                "Invalid group case " + i + " must clear the old group without changing the operator");
        }
    }

    @Test
    void malformedOperatorIdsClearPreviousOperatorAndCannotInjectHeaders() throws Exception
    {
        String[] invalid = {
            "", "unknown", "usr_synthetic", GROUP_ONE,
            "usr_1-1-1-1-1", "usr_33333333333343338333333333333333",
            "usr_gggggggg-3333-4333-8333-333333333333",
            " " + OPERATOR_ONE, OPERATOR_ONE + " ", OPERATOR_ONE + "; Group/" + GROUP_TWO,
            OPERATOR_ONE + "\r\nX-Injected: yes", OPERATOR_ONE + "\n", OPERATOR_ONE + "\r",
            OPERATOR_ONE + "\t", OPERATOR_ONE + "\u0000"
        };
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        for (int i = 0; i < invalid.length; i++)
        {
            ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
            ExtendedUserAgent.setCurrentUserId(invalid[i]);
            assertEquals(BASE + "; Group/" + GROUP_ONE, avatarHeader(),
                "Invalid operator case " + i + " must clear the old operator without changing the group");
        }
    }

    @Test
    void generalConnectionHelperDoesNotReceiveAvatarIdentity() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        HttpURLConnection general = connection();
        general.setInstanceFollowRedirects(true);

        ExtendedUserAgent.init_conn.invoke(general);

        assertEquals(Scarlet.USER_AGENT, general.getRequestProperty("User-Agent"));
        assertFalse(general.getRequestProperty("User-Agent").contains(GROUP_ONE));
        assertFalse(general.getRequestProperty("User-Agent").contains(OPERATOR_ONE));
        assertTrue(general.getInstanceFollowRedirects());
    }

    @Test
    void generalNoRedirectHelperKeepsGenericIdentityAndDisablesRedirects() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        HttpURLConnection general = connection();
        general.setInstanceFollowRedirects(true);

        ExtendedUserAgent.init_conn_disable_redirects.invoke(general);

        assertEquals(Scarlet.USER_AGENT, general.getRequestProperty("User-Agent"));
        assertFalse(general.getRequestProperty("User-Agent").contains(GROUP_ONE));
        assertFalse(general.getRequestProperty("User-Agent").contains(OPERATOR_ONE));
        assertFalse(general.getInstanceFollowRedirects());
    }

    @Test
    void legacyTargetArgumentCannotReplaceSignedInOperator() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);

        assertEquals(Scarlet.USER_AGENT, ExtendedUserAgent.getExtendedUserAgent(QUERY_TARGET));
        assertEquals(Scarlet.USER_AGENT, ExtendedUserAgent.getExtendedUserAgent(null));
        String header = avatarHeader();
        assertEquals(BASE + "; Group/" + GROUP_ONE + "; Operator/" + OPERATOR_ONE, header);
        assertFalse(header.contains(QUERY_TARGET));
    }

    @Test
    void lateProbeOrLoginCannotRestoreOperatorAfterIdentityIsCleared() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        long probeGeneration = ExtendedUserAgent.currentAuthenticationGeneration();

        ExtendedUserAgent.setCurrentUserId(null);
        assertNotEquals(probeGeneration, ExtendedUserAgent.currentAuthenticationGeneration());
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_ONE, probeGeneration));
        assertEquals(BASE + "; Group/" + GROUP_ONE, avatarHeader());

        long loginGeneration = ExtendedUserAgent.beginPrimaryAuthentication();
        assertTrue(ExtendedUserAgent.completePrimaryAuthentication(null, loginGeneration));
        assertNotEquals(loginGeneration, ExtendedUserAgent.currentAuthenticationGeneration());
        assertFalse(ExtendedUserAgent.completePrimaryAuthentication(OPERATOR_TWO, loginGeneration));
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_TWO, loginGeneration));
        assertEquals(BASE + "; Group/" + GROUP_ONE, avatarHeader());

        long retryGeneration = ExtendedUserAgent.beginPrimaryAuthentication();
        assertTrue(ExtendedUserAgent.completePrimaryAuthentication(OPERATOR_ONE, retryGeneration));
        long sessionGeneration = ExtendedUserAgent.currentAuthenticationGeneration();
        assertNotEquals(retryGeneration, sessionGeneration);
        assertTrue(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_ONE, sessionGeneration));
        assertEquals(sessionGeneration, ExtendedUserAgent.currentAuthenticationGeneration());
        assertTrue(ExtendedUserAgent.setCurrentUserIdIfUnchanged("invalid", sessionGeneration));
        assertNotEquals(sessionGeneration, ExtendedUserAgent.currentAuthenticationGeneration());
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_ONE, sessionGeneration));
        assertEquals(BASE + "; Group/" + GROUP_ONE, avatarHeader());
    }

    @Test
    void newestLoginWinsAndStaleUnauthorizedResponseCannotClearNewAccount() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        long olderLogin = ExtendedUserAgent.beginPrimaryAuthentication();
        assertEquals(BASE + "; Group/" + GROUP_ONE, avatarHeader());
        long newerLogin = ExtendedUserAgent.beginPrimaryAuthentication();
        assertNotEquals(olderLogin, newerLogin);

        assertTrue(ExtendedUserAgent.completePrimaryAuthentication(OPERATOR_TWO, newerLogin));
        long currentSession = ExtendedUserAgent.currentAuthenticationGeneration();
        assertNotEquals(newerLogin, currentSession);
        assertFalse(ExtendedUserAgent.completePrimaryAuthentication(OPERATOR_ONE, olderLogin));
        assertFalse(ExtendedUserAgent.completePrimaryAuthentication(null, olderLogin));
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(null, olderLogin));
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged("invalid", olderLogin));
        assertEquals(currentSession, ExtendedUserAgent.currentAuthenticationGeneration());
        assertEquals(BASE + "; Group/" + GROUP_ONE + "; Operator/" + OPERATOR_TWO, avatarHeader());
    }

    @Test
    void groupUpdateDuringLoginPreservesNewGroupAndAuthenticatedOperator() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        long loginGeneration = ExtendedUserAgent.beginPrimaryAuthentication();

        ExtendedUserAgent.setCurrentGroupId(GROUP_TWO);
        assertEquals(loginGeneration, ExtendedUserAgent.currentAuthenticationGeneration());
        assertEquals(BASE + "; Group/" + GROUP_TWO, avatarHeader());
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_ONE, loginGeneration));
        assertTrue(ExtendedUserAgent.completePrimaryAuthentication(OPERATOR_TWO, loginGeneration));
        assertEquals(BASE + "; Group/" + GROUP_TWO + "; Operator/" + OPERATOR_TWO, avatarHeader());
    }

    @Test
    void probesBeforeDuringAndAfterLoginCannotOverwriteTheCompletedAccount() throws Exception
    {
        ExtendedUserAgent.setCurrentGroupId(GROUP_ONE);
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        long beforeLoginProbe = ExtendedUserAgent.currentAuthenticationGeneration();
        long loginGeneration = ExtendedUserAgent.beginPrimaryAuthentication();
        long duringLoginProbe = ExtendedUserAgent.currentAuthenticationGeneration();
        assertEquals(loginGeneration, duringLoginProbe);

        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_ONE, beforeLoginProbe));
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_ONE, duringLoginProbe));
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(null, duringLoginProbe));
        assertEquals(BASE + "; Group/" + GROUP_ONE, avatarHeader());

        assertTrue(ExtendedUserAgent.completePrimaryAuthentication(OPERATOR_TWO, loginGeneration));
        long afterLoginProbe = ExtendedUserAgent.currentAuthenticationGeneration();
        assertNotEquals(duringLoginProbe, afterLoginProbe);
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_ONE, beforeLoginProbe));
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_ONE, duringLoginProbe));
        assertFalse(ExtendedUserAgent.setCurrentUserIdIfUnchanged(null, duringLoginProbe));
        assertFalse(ExtendedUserAgent.completePrimaryAuthentication(OPERATOR_ONE, loginGeneration));
        assertFalse(ExtendedUserAgent.completePrimaryAuthentication(OPERATOR_ONE, afterLoginProbe));
        assertTrue(ExtendedUserAgent.setCurrentUserIdIfUnchanged(OPERATOR_TWO, afterLoginProbe));
        assertEquals(afterLoginProbe, ExtendedUserAgent.currentAuthenticationGeneration());
        assertEquals(BASE + "; Group/" + GROUP_ONE + "; Operator/" + OPERATOR_TWO, avatarHeader());
    }

    @Test
    void snapshotIdentityIsStableOnlyOutsideAuthenticationTransitions()
    {
        ExtendedUserAgent.setCurrentUserId(OPERATOR_ONE);
        long initial = ExtendedUserAgent.currentAuthenticationGeneration();
        assertTrue(ExtendedUserAgent.isPrimaryAuthenticationStable(initial));

        long login = ExtendedUserAgent.beginPrimaryAuthentication();
        assertFalse(ExtendedUserAgent.isPrimaryAuthenticationStable(initial));
        assertFalse(ExtendedUserAgent.isPrimaryAuthenticationStable(login));

        assertTrue(ExtendedUserAgent.completePrimaryAuthentication(OPERATOR_TWO, login));
        long completed = ExtendedUserAgent.currentAuthenticationGeneration();
        assertTrue(ExtendedUserAgent.isPrimaryAuthenticationStable(completed));
        assertFalse(ExtendedUserAgent.isPrimaryAuthenticationStable(login));

        ExtendedUserAgent.setCurrentUserId(null);
        assertFalse(ExtendedUserAgent.isPrimaryAuthenticationStable(completed));
    }

    @Test
    void identifyingLookupsHaveDistinctConsentAndRemainDisabledWithoutIt()
    {
        assertEquals("avatar_search_identified_lookups_consent_v2", AvatarSearch.IDENTIFIED_LOOKUP_CONSENT_KEY);
        assertEquals(0, AvatarSearch.effectiveProviders(true, null, false, null, null).length);
        assertEquals(0, AvatarSearch.effectiveProviders(true, false, false, null, null).length);
    }
}
