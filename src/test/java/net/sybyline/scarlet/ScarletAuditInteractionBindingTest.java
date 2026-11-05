package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletAuditInteractionBindingTest
{
    @TempDir Path temp;
    ScarletSnapshotPrivacyTest.Fixture fixture() throws Exception
    {
        var owner = new ScarletSnapshotPrivacyTest(); owner.temp = temp;
        var f = owner.new Fixture();
        var vrc = inert(ScarletVRChat.class);
        put(vrc, "groupId", f.metadata.entry.getGroupId()); put(f.scarlet, "vrc", vrc);
        return f;
    }
    @Test void forgedOrMovedThreadComponentsNeverSelectAnAudit() throws Exception
    {
        var f = fixture(); String audit = ScarletSnapshotPrivacyTest.AUDIT;
        assertTrue(f.ui.trustedThreadOrigin(audit, f.origin, f.thread));
        f.author = "other-bot"; assertFalse(f.ui.trustedThreadOrigin(audit, f.origin, f.thread));
        f.author = "10"; f.webhook = true; assertFalse(f.ui.trustedThreadOrigin(audit, f.origin, f.thread));
        f.webhook = false; f.originId = "copied-message"; assertFalse(f.ui.trustedThreadOrigin(audit, f.origin, f.thread));
        f.originId = "101"; f.parentId = "other-channel"; assertFalse(f.ui.trustedThreadOrigin(audit, f.origin, f.thread));
        f.parentId = "20"; f.threadId = "other-thread"; assertFalse(f.ui.trustedThreadOrigin(audit, f.origin, f.thread));
    }
    @Test void postingPermissionNeverBypassesAuditBindingOrCurrentAudience() throws Exception
    {
        var f = fixture(); String audit = ScarletSnapshotPrivacyTest.AUDIT;
        assertNull(f.ui.auditBindingDenial(f.member, f.thread, audit));
        f.metadata.entryRedacted = true; f.save(); assertNotNull(f.ui.auditBindingDenial(f.member, f.thread, audit));
        f.metadata.entryRedacted = false; f.save(); f.secret.add("ordinary");
        assertNotNull(f.ui.auditBindingDenial(f.member, f.thread, audit));
        f.secret.clear(); f.channelAccess = false; assertNotNull(f.ui.auditBindingDenial(f.member, f.thread, audit));
        f.channelAccess = true; f.missingMember = true; assertNotNull(f.ui.auditBindingDenial(f.member, f.thread, audit));
        f.missingMember = false; put(f.scarlet.vrc, "groupId", "grp_other");
        assertNotNull(f.ui.auditBindingDenial(f.member, f.thread, audit));
    }
}
