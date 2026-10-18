package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;
import static net.sybyline.scarlet.ScarletDiscordReadinessTest.proxy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.Prop;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

class ScarletPropSpawnEmbedTest
{
    private static final String SPAWNER = "usr_00000000-0000-0000-0000-000000000001";
    private static final String AUTHOR = "usr_00000000-0000-0000-0000-000000000002";
    private static final String PROP = "prop_00000000-0000-0000-0000-000000000003";

    private MessageEmbed render(Prop prop)
    {
        return ScarletDiscordJDA.propSpawnEmbed("synthetic-location", SPAWNER, "Test User", PROP, prop, "").build();
    }

    private static String field(MessageEmbed embed, String name)
    {
        return embed.getFields().stream().filter(value -> name.equals(value.getName()))
            .map(MessageEmbed.Field::getValue).findFirst().orElse(null);
    }

    @Test void missingMetadataStillDescribesObservedSpawn()
    {
        MessageEmbed embed = render(null);
        assertEquals("Test User spawned a prop", embed.getTitle());
        assertEquals("`"+SPAWNER+"`", field(embed, "User ID"));
        assertEquals("`"+PROP+"`", field(embed, "Prop ID"));
        assertTrue(field(embed, "Prop Author").contains("could not be verified"));
        assertNull(field(embed, "Report prop"), "Do not falsely report the spawner as content author");
        assertNull(embed.getImage());
    }

    @Test void incompleteMetadataDoesNotInventContentAuthor()
    {
        MessageEmbed embed = render(new Prop());
        assertNotNull(field(embed, "Prop ID"));
        assertNull(field(embed, "Report prop"));
        assertFalse(field(embed, "Prop Author").contains(SPAWNER));
    }

    @Test void knownAuthorWithoutDisplayNameUsesTheirOwnIdentifier()
    {
        MessageEmbed embed = render(new Prop().authorId(AUTHOR));
        assertTrue(field(embed, "Prop Author").contains(AUTHOR));
        assertNotNull(field(embed, "Report prop"));
        assertTrue(field(embed, "Report prop").contains(AUTHOR));
        assertFalse(field(embed, "Report prop").contains(SPAWNER));
    }

    @Test void availableMetadataRetainsAuthorAndPreview()
    {
        MessageEmbed embed = render(new Prop().authorId(AUTHOR).authorName("Content Author")
            .thumbnailImageUrl("https://example.invalid/thumbnail.png").imageUrl("https://example.invalid/image.png"));
        assertTrue(field(embed, "Prop Author").contains("Content Author"));
        assertEquals("https://example.invalid/thumbnail.png", embed.getImage().getUrl());
        assertNotNull(field(embed, "Report prop"));
    }

    @Test void blankAuthorIdDoesNotCreateFalseAuthorOrReport()
    {
        MessageEmbed embed = render(new Prop().authorId("   ").authorName("Unverified Name"));
        assertTrue(field(embed, "Prop Author").contains("could not be verified"));
        assertFalse(field(embed, "Prop Author").contains("Unverified Name"));
        assertNull(field(embed, "Report prop"));
    }

    static class CapturingDiscord extends ScarletDiscordJDA
    {
        GuildMessageChannel channel;
        int routeCalls;
        CapturingDiscord() { super(null, null, null); throw new AssertionError("Never start the application in tests"); }

        @Override void condEmitEx(GroupAuditTypeEx type, boolean log, boolean secret, String location, CondEmit emit)
        {
            assertEquals(GroupAuditTypeEx.SPAWN_PROP, type);
            assertFalse(log);
            assertFalse(secret);
            assertEquals("synthetic-location", location);
            routeCalls++;
            emit.emit("123", null, channel);
        }
    }

    @Test void actualEmitterCompletesForMissingPartialAndAvailableMetadataWithoutNetwork() throws Exception
    {
        CapturingDiscord discord = inert(CapturingDiscord.class);
        ScarletSettings settings = inert(ScarletSettings.class);
        put(settings, "fileValuedSettings", new HashMap<>());
        ScarletSettings.FileValuedStringPattern reportEmail = settings.new FileValuedStringPattern(
            "synthetic_report_email", "Synthetic report email", "", null, false);
        put(reportEmail, "cached", "reports@example.invalid");
        put(discord, "requestingEmail", reportEmail);

        List<MessageEmbed> sent = new ArrayList<>();
        AtomicInteger componentCalls = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        Message message = proxy(Message.class, (p, method, args) -> {
            if (method.getName().equals("getId")) return "123";
            throw new AssertionError("Unexpected message operation " + method.getName());
        });
        MessageCreateAction action = proxy(MessageCreateAction.class, (p, method, args) -> {
            if (method.getName().equals("addComponents"))
            {
                componentCalls.incrementAndGet();
                return p;
            }
            if (method.getName().equals("complete"))
            {
                completed.incrementAndGet();
                return message;
            }
            throw new AssertionError("Unexpected outbound operation " + method.getName());
        });
        discord.channel = proxy(GuildMessageChannel.class, (p, method, args) -> {
            if (method.getName().equals("sendMessageEmbeds"))
            {
                Object first = args[0];
                sent.add(first instanceof Collection ? (MessageEmbed)((Collection<?>)first).iterator().next() : (MessageEmbed)first);
                return action;
            }
            throw new AssertionError("Unexpected channel operation " + method.getName());
        });

        for (Prop prop : new Prop[]{null, new Prop(), new Prop().authorId(AUTHOR).authorName("Content Author")})
            discord.emitExtendedUserSpawnProp(null, LocalDateTime.of(2026, 10, 4, 11, 12, 30),
                "synthetic-location", SPAWNER, "Test User", PROP, prop);

        assertEquals(3, discord.routeCalls);
        assertEquals(3, sent.size());
        assertEquals(3, componentCalls.get());
        assertEquals(3, completed.get(), "Each valid spawn must reach completed delivery without a metadata prerequisite");
        for (MessageEmbed embed : sent)
        {
            assertEquals("`"+SPAWNER+"`", field(embed, "User ID"));
            assertEquals("`"+PROP+"`", field(embed, "Prop ID"));
            assertEquals("https://vrchat.com/home/user/"+SPAWNER, embed.getUrl());
        }
        for (MessageEmbed embed : sent.subList(0, 2))
        {
            assertNull(field(embed, "Report prop"));
            assertFalse(field(embed, "Prop Author").contains(SPAWNER));
            assertNull(embed.getImage());
        }
        assertTrue(field(sent.get(2), "Report prop").contains(AUTHOR));
        assertFalse(field(sent.get(2), "Report prop").contains(SPAWNER));
    }
}
