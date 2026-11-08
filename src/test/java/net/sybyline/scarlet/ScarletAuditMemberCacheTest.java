package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletAuditPrivacyEntryPointsTest.proxy;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.handle.EventCache;
import net.dv8tion.jda.internal.entities.EntityBuilder;
import net.dv8tion.jda.internal.entities.GuildImpl;
import net.dv8tion.jda.internal.entities.MemberImpl;
import net.dv8tion.jda.internal.entities.SelfUserImpl;
import net.dv8tion.jda.internal.entities.UserImpl;
import net.dv8tion.jda.internal.utils.cache.SnowflakeCacheViewImpl;
import net.dv8tion.jda.internal.utils.config.MetaConfig;

/** Real JDA cache objects with an inert client: no gateway, HTTP, account or app startup. */
class ScarletAuditMemberCacheTest
{
    @TempDir Path temp;

    static Object builderField(JDABuilder builder, String name) throws Exception
    {
        Field field = JDABuilder.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(builder);
    }

    static MemberCachePolicy policy(JDABuilder builder) throws Exception
    {
        return (MemberCachePolicy) builderField(builder, "memberCachePolicy");
    }

    static final class CacheFixture
    {
        final JDAImpl jda = inert(JDAImpl.class);
        final GuildImpl guild;
        final MemberImpl member;
        final EntityBuilder entities;

        CacheFixture(MemberCachePolicy policy) throws Exception
        {
            put(jda, "userCache", new SnowflakeCacheViewImpl<User>(User.class, User::getName));
            put(jda, "guildCache", new SnowflakeCacheViewImpl<Guild>(Guild.class, Guild::getName));
            put(jda, "metaConfig", MetaConfig.getDefault());
            put(jda, "eventCache", new EventCache());
            jda.setSelfUser(new SelfUserImpl(10L, jda));
            jda.setMemberCachePolicy(policy);
            guild = new GuildImpl(jda, 1L).setOwnerId(99L);
            try (var lock = jda.getGuildsView().writeLock())
            {
                jda.getGuildsView().getMap().put(1L, guild);
            }
            member = new MemberImpl(guild, new UserImpl(50L, jda).setName("Synthetic staff"));
            entities = new EntityBuilder(jda);
        }
    }

    @Test void configuredPolicyRetainsOrdinaryMembersAndOwnerWithoutRequestingMemberChunks() throws Exception
    {
        JDABuilder builder = ScarletDiscordJDA.gatewayBuilder(null);
        CacheFixture f = new CacheFixture(policy(builder));
        assertFalse(f.member.isOwner());
        assertTrue(f.member.getVoiceState() == null || !f.member.getVoiceState().inAudioChannel());
        assertTrue(policy(builder).cacheMember(f.member));
        f.guild.setOwnerId(50L);
        assertTrue(f.member.isOwner());
        assertTrue(policy(builder).cacheMember(f.member));
        int intents = (Integer) builderField(builder, "intents");
        assertTrue((intents & GatewayIntent.GUILD_MEMBERS.getRawValue()) != 0);
        assertTrue((intents & GatewayIntent.MESSAGE_CONTENT.getRawValue()) != 0);
        assertSame(ChunkingFilter.NONE, builderField(builder, "chunkingFilter"));
    }

    @Test void actualJdaInteractionCacheInsertionFixesTheDefaultPolicyMiss() throws Exception
    {
        CacheFixture f = new CacheFixture(MemberCachePolicy.DEFAULT);
        assertFalse(f.entities.updateMemberCache(f.member));
        assertNull(f.guild.getMemberById("50"));
        f.jda.setMemberCachePolicy(policy(ScarletDiscordJDA.gatewayBuilder(null)));
        assertTrue(f.entities.updateMemberCache(f.member));
        assertSame(f.member, f.guild.getMemberById("50"));
        assertSame(f.member.getUser(), f.jda.getUserById("50"));
        // This is the member-cache removal performed by JDA's guild-member removal handler.
        assertSame(f.member, f.guild.getMembersView().remove(50L));
        assertNull(f.guild.getMemberById("50"));
    }

    @Test void auditGatesFollowRealCacheInsertionAndRemovalWithoutTrustingOldInteractionMember() throws Exception
    {
        CacheFixture cache = new CacheFixture(policy(ScarletDiscordJDA.gatewayBuilder(null)));
        var test = new ScarletSnapshotPrivacyTest(); test.temp = temp;
        var f = test.new Fixture();
        var vrc = inert(ScarletVRChat.class);
        f.metadata.entry.setGroupId("grp_10000000-0000-0000-0000-000000000001");
        put(vrc, "groupId", f.metadata.entry.getGroupId()); put(f.scarlet, "vrc", vrc);
        Guild originalGuild = f.guild;
        put(f, "guild", proxy(Guild.class, (p,m,a) -> {
            if (m.getName().equals("getMemberById"))
                return cache.guild.getMemberById(String.valueOf(a[0])) == null ? null : f.member;
            return m.invoke(originalGuild, a);
        }));
        f.save();
        assertAuditAccess(f, false);
        assertTrue(cache.entities.updateMemberCache(cache.member));
        assertAuditAccess(f, true);
        // Removing the cached member must deny even while the original interaction object survives.
        cache.guild.getMembersView().remove(50L);
        assertAuditAccess(f, false);
        assertTrue(cache.entities.updateMemberCache(cache.member));
        f.channelAccess = false;
        assertAuditAccess(f, false);
        f.channelAccess = true;
        f.metadata.entryRedacted = true; f.save();
        assertAuditAccess(f, false);
    }

    static void assertAuditAccess(ScarletSnapshotPrivacyTest.Fixture f, boolean allowed)
    {
        assertEquals(allowed, f.ui.auditBindingDenial(f.member, f.thread, ScarletSnapshotPrivacyTest.AUDIT) == null);
        for (String operation : ScarletSnapshotPrivacyTest.OPERATIONS)
        {
            f.command = operation;
            assertEquals(allowed, f.ui.snapshotAllowed(f.metadata, f.requester, f.origin, operation), operation);
        }
    }
}
