package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScarletFastInstanceSelectionTest {
    @Test void pollOrderDoesNotChangeDestinationAndKnownInstancesStayExcluded() {
        for(List<String> order:List.of(List.of("old","z","a","bot"),List.of("a","bot","old","z")))
            assertEquals("a",Scarlet.selectNewInstance(new LinkedHashSet<>(order),Set.of("old"),"bot"));
        assertNull(Scarlet.selectNewInstance(Set.of("old","bot"),Set.of("old"),"bot"));
        assertNull(Scarlet.selectNewInstance(Set.of(),Set.of(),null));
    }
}
