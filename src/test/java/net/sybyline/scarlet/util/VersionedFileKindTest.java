package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import io.github.vrchatapi.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionedFileKindTest {
    @Test void kindInitializesAndClassifiesSparseFiles() {
        assertEquals(VersionedFile.Kind.OTHER,VersionedFile.Kind.of(null));
        assertEquals(VersionedFile.Kind.OTHER,VersionedFile.Kind.of(new ModelFile()));
        assertEquals(VersionedFile.Kind.AVATAR,VersionedFile.Kind.of(new ModelFile().extension(".vrca")));
        assertEquals(VersionedFile.Kind.WORLD,VersionedFile.Kind.of(new ModelFile().mimeType(MIMEType.APPLICATION_X_WORLD)));
        assertEquals(VersionedFile.Kind.EMOJI_ANIMATED,VersionedFile.Kind.of(new ModelFile().tags(List.of("emoji","animated"))));
        assertEquals(VersionedFile.Kind.AVATAR_IMAGE,VersionedFile.Kind.of(new ModelFile().name("Avatar - Example - Image - 2022.3.22f1_1_1_standalonewindows_Release")));
        assertEquals(VersionedFile.Kind.OTHER,VersionedFile.Kind.of(new ModelFile().name("Avatar - incomplete")));
    }
}
