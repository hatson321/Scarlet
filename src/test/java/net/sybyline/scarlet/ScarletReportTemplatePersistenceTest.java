package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletReportTemplatePersistenceTest {
    @TempDir Path temp;
    @Test void failedPersistencePreservesPublishedTemplateAndDisk() throws Exception {
        Path file=temp.resolve("template.txt"); Files.writeString(file,"original");
        ScarletVRChatReportTemplate template=new ScarletVRChatReportTemplate(file.toFile()) {
            @Override void persist(String contents) throws IOException { throw new IOException("synthetic commit failure"); }
        };
        assertThrows(IOException.class,()->template.trySet("unsaved"));
        assertEquals("original",template.get()); assertEquals("original",Files.readString(file));
        ScarletVRChatReportTemplate replacement=new ScarletVRChatReportTemplate(file.toFile());
        assertTrue(replacement.trySet("saved")); assertEquals("saved",replacement.get());
        assertEquals("saved",new ScarletVRChatReportTemplate(file.toFile()).get());
        assertFalse(replacement.trySet(" "));
    }
}
