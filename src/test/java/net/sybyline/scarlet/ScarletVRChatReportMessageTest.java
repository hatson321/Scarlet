package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

class ScarletVRChatReportMessageTest
{
    @TempDir Path temporary;

    @Test void restoresTemplateFormAlongsideCopyTextAndSimpleForm() throws Exception
    {
        String html = "Target: Example<br>Custom context &amp; evidence<ul><li>Reviewed tag</li></ul>";
        try (MessageCreateData reply = ScarletVRChatReportMessage.create(
                "moderator@example.invalid", "usr-example-target", "Example", "Simple notes", html))
        {
            assertEquals(2, reply.getEmbeds().size());
            assertTrue(reply.getEmbeds().get(0).getTitle().contains("configured template"));
            assertEquals(html, query(link(reply, 0)).get("tf_description"));
            assertEquals("Simple notes", query(link(reply, 1)).get("tf_description"));
            assertTrue(reply.getContent().contains("Custom context & evidence\n- Reviewed tag"));
            assertTrue(reply.getFiles().isEmpty());
            assertTrue(reply.getAllowedMentions().isEmpty());
            assertFalse(reply.getContent().contains("audit actor"));
            assertDiscordLimits(reply);
        }
    }

    @Test void preservesTargetEmailUnicodeNameAndReservedCharacters() throws Exception
    {
        String subject = "\u0394elta & Tester";
        String html = "Reason: A+B &amp; C? #detail = 50%<br>\u65e5\u672c\u8a9e";
        try (MessageCreateData reply = ScarletVRChatReportMessage.create(
                "moderator+reports@example.invalid", "usr-example-target", subject, "Notes", html))
        {
            Map<String, String> fields = query(link(reply, 0));
            assertEquals("https", new URI(link(reply, 0)).getScheme());
            assertEquals("help.vrchat.com", new URI(link(reply, 0)).getHost());
            assertEquals("41536165070483", fields.get("ticket_form_id"));
            assertEquals("usr-example-target", fields.get("tf_41537175838995"));
            assertEquals("moderator+reports@example.invalid", fields.get("tf_anonymous_requester_email"));
            assertEquals("user_report", fields.get("tf_360056455174"));
            assertEquals("account_report", fields.get("tf_41535925078291"));
            assertTrue(fields.get("tf_description").contains("\u0394elta &amp; Tester"));
            assertTrue(fields.get("tf_description").endsWith(html));
            assertTrue(reply.getContent().contains("\u65e5\u672c\u8a9e"));
        }
    }

    @Test void configuredPlaceholdersAndOptionalFooterReachBothOutputs() throws Exception
    {
        ScarletVRChatReportTemplate template = new ScarletVRChatReportTemplate(temporary.resolve("report.txt").toFile());
        assertTrue(template.trySet("Target: {targetName}<br>Reason: {description}<br>Tags: {tags}"));
        ScarletVRChatReportTemplate.FormatParams params = template.new FormatParams()
            .target(null, "usr-example-target", "Example")
            .audit("audit-example", "Observed behavior", new String[] { "Rule A", "Rule B" });
        params.groupId = "example-group";
        for (boolean footer : new boolean[] { false, true })
        {
            String html = params.format(footer);
            try (MessageCreateData reply = ScarletVRChatReportMessage.create(null, "usr-example-target", "Example", "Notes", html))
            {
                assertEquals(html, query(link(reply, 0)).get("tf_description"));
                assertTrue(reply.getContent().contains("Reason: Observed behavior\nTags: Rule A, and Rule B"));
                assertEquals(footer, reply.getContent().contains("Partially autofilled with"));
                assertEquals(footer, query(link(reply, 0)).get("tf_description").contains("Audit ID: audit-example"));
            }
        }
    }

    @Test void emptyTemplateUsesEventDetails() throws Exception
    {
        for (String empty : new String[] { null, "", "  \n" })
        {
            try (MessageCreateData reply = ScarletVRChatReportMessage.create(null, "usr-example", null,
                    "Event notes<br>Tags: Reviewed<br>Audit ID: example-audit", empty))
            {
                assertEquals(query(link(reply, 1)), query(link(reply, 0)));
                assertTrue(reply.getContent().contains("Event notes\nTags: Reviewed\nAudit ID: example-audit"));
            }
        }
    }

    @Test void longReportsAndLinksArePreservedCompletely() throws Exception
    {
        String html = String.join("", Collections.nCopies(350, "Evidence \u65e5\u672c\u8a9e &amp; details<br>"));
        try (MessageCreateData reply = ScarletVRChatReportMessage.create(null, "usr-example", "Example", html, html))
        {
            assertEquals(3, reply.getFiles().size());
            assertEquals(ScarletVRChatReportMessage.reportHtmlToPlain(html), attachment(reply, "vrchat-report.txt"));
            assertEquals(html, query(attachment(reply, "vrchat-templated-form-link.txt")).get("tf_description"));
            assertEquals(html, query(attachment(reply, "vrchat-simple-form-link.txt")).get("tf_description"));
            assertTrue(reply.getContent().contains("complete copyable report is attached"));
            assertDiscordLimits(reply);
        }
    }

    @Test void twoLargeInlineLinksStayInsideCombinedEmbedBudget() throws Exception
    {
        String html = String.join("", Collections.nCopies(1700, "x"));
        try (MessageCreateData reply = ScarletVRChatReportMessage.create(null, "usr-example", "Example", html, html))
        {
            assertEquals(html, query(link(reply, 0)).get("tf_description"));
            assertEquals(html, query(link(reply, 1)).get("tf_description"));
            assertEquals(1, reply.getFiles().size());
            assertDiscordLimits(reply);
        }
    }

    @Test void embeddedCodeFencesCannotEscapePreviewAndOriginalRemainsCopyable() throws Exception
    {
        for (int run : new int[] { 1, 3, 5, 6, 15 })
        {
            String ticks = String.join("", Collections.nCopies(run, "`"));
            String plain = "Before " + ticks + " @everyone " + ticks + " after";
            try (MessageCreateData reply = ScarletVRChatReportMessage.create(null, null, null, "Notes", plain))
            {
                assertEquals(2, reply.getContent().split("```", -1).length - 1);
                assertEquals(plain, attachment(reply, "vrchat-report.txt"));
                assertTrue(reply.getAllowedMentions().isEmpty());
                assertDiscordLimits(reply);
            }
        }
    }

    @Test void htmlEntitiesAreDecodedOnceAfterTagsAreRemoved()
    {
        assertEquals("A & B\n- <value> \"quoted\" 'text' &lt;",
            ScarletVRChatReportMessage.reportHtmlToPlain("A &amp; B<br/><ul><li>&lt;value&gt; &quot;quoted&quot; &#39;text&#39;&nbsp;&amp;lt;</li></ul>"));
        assertEquals("", ScarletVRChatReportMessage.reportHtmlToPlain(null));
    }

    @Test void previewDoesNotSplitSupplementaryUnicodeCharacters() throws Exception
    {
        String text = String.join("", Collections.nCopies(1498, "x")) + "\ud83d\ude42" + "end";
        try (MessageCreateData reply = ScarletVRChatReportMessage.create(null, "usr-example", "Example", "Notes", text))
        {
            assertFalse(reply.getContent().contains("\ud83d\u2026"));
            assertEquals(text, attachment(reply, "vrchat-report.txt"));
            assertDiscordLimits(reply);
        }
    }

    private static String link(MessageCreateData reply, int index)
    {
        String description = reply.getEmbeds().get(index).getDescription();
        return description.substring(description.indexOf("(<") + 2, description.indexOf(">)"));
    }

    private static Map<String, String> query(String url) throws Exception
    {
        Map<String, String> result = new LinkedHashMap<>();
        for (String parameter : new URI(url).getRawQuery().split("&"))
        {
            String[] pair = parameter.split("=", 2);
            result.put(URLDecoder.decode(pair[0], "UTF-8"), URLDecoder.decode(pair[1], "UTF-8"));
        }
        return result;
    }

    private static String attachment(MessageCreateData reply, String name) throws Exception
    {
        FileUpload file = reply.getFiles().stream().filter(f -> name.equals(f.getName())).findFirst().get();
        try (InputStream in = file.getData(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1)
                out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void assertDiscordLimits(MessageCreateData reply)
    {
        assertTrue(reply.getContent().length() <= 2000);
        int total = 0;
        for (MessageEmbed embed : reply.getEmbeds())
        {
            assertTrue(embed.getDescription().length() <= 4096);
            total += embed.getTitle().length() + embed.getDescription().length();
        }
        assertTrue(total <= 6000);
        assertNotNull(reply.toData());
    }
}
