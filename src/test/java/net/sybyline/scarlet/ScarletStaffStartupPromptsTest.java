package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import io.github.vrchatapi.model.GroupPermissions;

/** Calls the actual startup prompt paths without constructors, credentials, network or UI. */
class ScarletStaffStartupPromptsTest
{
    static class Vrc extends ScarletVRChat
    {
        boolean valid, permitted;
        int groupChecks, permissionChecks, groupPrompts, permissionPrompts;
        Vrc() { super(null, null, null); throw new AssertionError(); }
        @Override public boolean hasValidGroupId() { groupChecks++; return valid; }
        @Override public boolean checkSelfUserHasVRChatPermission(GroupPermissions permission)
        { assertEquals(GroupPermissions.group_audit_view, permission); permissionChecks++; return permitted; }
        @Override public void modalNeedGroupId() { groupPrompts++; }
        @Override public void modalNeedPerms(GroupPermissions permission) { permissionPrompts++; }
    }

    static class Settings extends ScarletSettings
    {
        int prompts;
        Runnable yes, no;
        Settings() { super(null, null); throw new AssertionError(); }
        @Override public void requireConfirmYesNoAsync(String message, String title, Runnable yes, Runnable no)
        { assertEquals("Discord Moderation Commands", title); prompts++; this.yes = yes; this.no = no; }
    }

    @Test void staffModeSkipsGroupLookupAndPermissionQueriesEntirely() throws Exception
    {
        Scarlet s = ScarletDiscordSafetyTest.inert(Scarlet.class);
        Vrc v = ScarletDiscordSafetyTest.inert(Vrc.class);
        ScarletDiscordSafetyTest.put(s, "vrc", v);
        s.staffMode = true;
        s.checkGroupStartupPermissions();
        v.valid = true; s.checkGroupStartupPermissions();
        assertEquals(0, v.groupChecks); assertEquals(0, v.permissionChecks);
        assertEquals(0, v.groupPrompts); assertEquals(0, v.permissionPrompts);
    }

    @Test void groupBotRetainsMissingGroupAndPermissionWarnings() throws Exception
    {
        Scarlet s = ScarletDiscordSafetyTest.inert(Scarlet.class);
        Vrc v = ScarletDiscordSafetyTest.inert(Vrc.class);
        ScarletDiscordSafetyTest.put(s, "vrc", v);
        s.checkGroupStartupPermissions();
        assertEquals(1, v.groupPrompts); assertEquals(0, v.permissionChecks);
        v.valid = true; s.checkGroupStartupPermissions();
        assertEquals(1, v.permissionPrompts);
        v.permitted = true; s.checkGroupStartupPermissions();
        assertEquals(1, v.permissionPrompts); assertEquals(2, v.permissionChecks);
    }

    @Test void staffModeDoesNotConsumeDiscordOptInAndBotModeStillOffersIt() throws Exception
    {
        assertTrue(Features.DISCORD_KICK_BAN_ENABLED);
        Scarlet s = ScarletDiscordSafetyTest.inert(Scarlet.class);
        Settings settings = ScarletDiscordSafetyTest.inert(Settings.class);
        ScarletSettings.FileValued<Boolean> prompted = ScarletStaffModeAlertsTest.flag(false);
        ScarletSettings.FileValued<Boolean> enabled = ScarletStaffModeAlertsTest.flag(false);
        ScarletDiscordSafetyTest.put(s, "settings", settings);
        ScarletDiscordSafetyTest.put(s, "discordKickBanPrompted", prompted);
        ScarletDiscordSafetyTest.put(s, "discordKickBanEnabled", enabled);
        s.staffMode = true; s.maybeOfferDiscordModeration();
        assertEquals(0, settings.prompts); assertFalse(prompted.get()); assertFalse(enabled.get());
        s.staffMode = false; s.maybeOfferDiscordModeration();
        assertEquals(1, settings.prompts); assertNotNull(settings.yes); assertNotNull(settings.no);
        prompted.cached = true; s.maybeOfferDiscordModeration();
        assertEquals(1, settings.prompts);
    }
}
