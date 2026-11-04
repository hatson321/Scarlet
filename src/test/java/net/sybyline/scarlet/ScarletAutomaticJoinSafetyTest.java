package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import net.sybyline.scarlet.ext.ExtendedUserAgent;

/** Inert API and log probes: never reaches process discovery or a game launch. */
class ScarletAutomaticJoinSafetyTest
{
    static class Vrc extends ScarletVRChat
    {
        String response;
        boolean fail;
        int calls;
        Runnable afterRead;
        Vrc() { super(null, null, null); throw new AssertionError("No real API construction"); }
        @Override String getStartupInstanceSecureName(String group, String location, BooleanSupplier allowed) throws IOException
        {
            assertTrue(allowed.getAsBoolean());
            assertEquals(groupId, group);
            calls++;
            if (afterRead != null) afterRead.run();
            if (fail) throw new IOException("synthetic lookup failure");
            return response;
        }
        @Override public String getInstanceSecureName(String world, String instance) { return null; }
        @Override public io.github.vrchatapi.model.Instance getInstance(String world, String instance) { return null; }
    }
    static class GuardedLogs extends ScarletVRChatLogs
    {
        GuardedLogs() { super(null); }
        @Override ScarletStartupRecovery.LogState startupLogState()
        { throw new AssertionError("Failed or cancelled join must stop before any client action"); }
    }

    @Test void failedOrCancelledSecureLookupCannotReachClientReplacement() throws Exception
    {
        for (String scenario : new String[]{"null", "blank", "exception", "cancel", "training"})
        {
            Scarlet scarlet = inert(Scarlet.class);
            ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
            Vrc vrc = inert(Vrc.class);
            vrc.groupId = ScarletStartupFollowIntegrationTest.GROUP;
            vrc.currentUserId = "usr_11111111-1111-1111-1111-111111111111";
            put(scarlet, "vrc", vrc);
            put(scarlet, "running", true);
            put(scarlet, "logs", new GuardedLogs());
            put(discord, "scarlet", scarlet);
            ScarletSettings settings = inert(ScarletSettings.class);
            put(settings, "fileValuedSettings", new HashMap<>());
            ScarletSettings.FileValuedBoolean follow = settings.new FileValuedBoolean("synthetic_follow", "Synthetic", true);
            ScarletSettings.FileValuedBoolean training = settings.new FileValuedBoolean("synthetic_training", "Synthetic", false);
            put(follow, "cached", true);
            put(training, "cached", false);
            put(scarlet, "trainingMode", training);
            put(discord, "vrchatClient_launchOnInstanceCreate", follow);
            if (scenario.equals("blank")) vrc.response = "  ";
            if (scenario.equals("exception")) vrc.fail = true;
            if (scenario.equals("cancel") || scenario.equals("training"))
            {
                vrc.response = "synthetic-secure";
                vrc.afterRead = () -> {
                    try { put(scenario.equals("training") ? training : follow, "cached", scenario.equals("training")); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                };
            }
            ExtendedUserAgent.setCurrentUserId(vrc.currentUserId);
            try
            {
                Method launch = ScarletDiscordJDA.class.getDeclaredMethod("runClientLaunch", String.class);
                launch.setAccessible(true);
                launch.invoke(discord, ScarletStartupFollowIntegrationTest.LOCATION);
                assertEquals(1, vrc.calls, scenario);
            }
            finally { ExtendedUserAgent.setCurrentUserId(null); }
        }
    }
}
