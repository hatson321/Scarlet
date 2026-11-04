package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.StringReader;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;

class ScarletSettingsDocumentationTest {
    @Test void allIllustratedJsonParsesAfterRemovingDocumentedCommentLines() throws Exception {
        String text = new String(Files.readAllBytes(Paths.get("SETTINGS.md")), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("(?s)```json\\R(.*?)```").matcher(text);
        int count = 0;
        while (matcher.find()) {
            String json = matcher.group(1).replaceAll("(?m)^\\s*//[^\\r\\n]*", "");
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);
            assertNotNull(JsonParser.parseReader(reader), "Example " + count);
            count++;
        }
        assertTrue(count >= 8);
        assertFalse(text.contains("\"scarletAuxWh2webhookUrl \""));
        assertTrue(text.contains("\"vrchat_report_template_footer\": true"));
        assertEquals(1, text.split("\\\"tts_announce_watched_groups\\\"\\s*:", -1).length - 1);
    }
}
