package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import static net.sybyline.scarlet.ScarletAuditPrivacyEntryPointsTest.proxy;

import java.nio.file.Path;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletAuditAccessDiagnosticsTest
{
    @TempDir Path temp;

    ScarletSnapshotPrivacyTest.Fixture fixture() throws Exception
    {
        ScarletSnapshotPrivacyTest test = new ScarletSnapshotPrivacyTest(); test.temp = temp;
        ScarletSnapshotPrivacyTest.Fixture f = test.new Fixture();
        ScarletVRChat vrc = inert(ScarletVRChat.class);
        put(vrc, "groupId", f.metadata.entry.getGroupId()); put(f.scarlet, "vrc", vrc);
        return f;
    }

    void denied(ScarletSnapshotPrivacyTest.Fixture f, String code)
    {
        String result = f.ui.auditBindingDenial(f.member, f.thread, ScarletSnapshotPrivacyTest.AUDIT);
        assertNotNull(result);
        assertTrue(result.endsWith("[" + code + "]"), result);
        assertFalse(result.contains(ScarletSnapshotPrivacyTest.AUDIT));
        assertFalse(result.contains(ScarletSnapshotPrivacyTest.USER));
    }

    @Test void accessFailuresHaveDistinctSafeReasons() throws Exception
    {
        var f = fixture();
        assertNull(f.ui.auditBindingDenial(f.member, f.thread, ScarletSnapshotPrivacyTest.AUDIT));
        f.missingMember = true; denied(f, "AUD-MEMBER"); f.missingMember = false;
        f.channelAccess = false; denied(f, "AUD-VIEW"); f.channelAccess = true;
        f.metadata.entryRedacted = true; f.save(); denied(f, "AUD-REDACTED");
        f.metadata.entryRedacted = false; f.save();
        f.secret.add("ordinary"); denied(f, "AUD-AUDIENCE"); f.secret.clear();
        f.parentId = "changed-parent"; denied(f, "AUD-THREAD"); f.parentId = "20";
        f.guildId = "2"; denied(f, "AUD-GUILD"); f.guildId = "1";
        put(f.scarlet.vrc, "groupId", "grp_other"); denied(f, "AUD-METADATA");
    }

    @Test void readHistoryIsDistinguishedFromChannelVisibility() throws Exception
    {
        var f = fixture(); Member original = f.member;
        f.member = proxy(Member.class, (p,m,a) -> {
            if (m.getName().equals("hasPermission"))
                for (Object arg : a)
                    if (arg instanceof Permission[])
                        for (Permission permission : (Permission[])arg)
                            if (permission == Permission.MESSAGE_HISTORY) return false;
            return m.invoke(original, a);
        });
        denied(f, "AUD-HISTORY");
        assertTrue(f.ui.snapshotDenial(f.metadata, f.requester, f.origin, f.command).endsWith("[AUD-HISTORY]"));
    }

    @Test void snapshotsKeepCurrentOriginAudienceAndRevocationChecks() throws Exception
    {
        var f = fixture();
        assertNull(f.ui.snapshotDenial(f.metadata, f.requester, f.origin, f.command));
        f.missingMember = true;
        assertTrue(f.ui.snapshotDenial(f.metadata, f.requester, f.origin, f.command).endsWith("[AUD-MEMBER]"));
        f.missingMember = false; f.permission = false;
        assertTrue(f.ui.snapshotDenial(f.metadata, f.requester, f.origin, f.command).endsWith("[AUD-OPERATION]"));
        f.permission = true; f.author = "other-bot";
        assertTrue(f.ui.snapshotDenial(f.metadata, f.requester, f.origin, f.command).endsWith("[AUD-SNAPSHOT-ORIGIN]"));
        f.author = "10"; f.secret.add("ordinary");
        assertTrue(f.ui.snapshotDenial(f.metadata, f.requester, f.origin, f.command).endsWith("[AUD-AUDIENCE]"));
        f.secret.clear(); f.metadata.entryRedacted = true; f.save();
        assertTrue(f.ui.snapshotDenial(f.metadata, f.requester, f.origin, f.command).endsWith("[AUD-REDACTED]"));
    }
}
