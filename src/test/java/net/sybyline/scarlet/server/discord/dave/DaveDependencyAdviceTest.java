package net.sybyline.scarlet.server.discord.dave;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DaveDependencyAdviceTest {
    @Test void onlyConfirmedOpusFailuresOfferAnOpusInstall() {
        assertTrue(DaveLibraryLoader.canInstallOpus(DaveLibraryLoader.identifyMissingLibrary("libopus.so.0: missing")));
        for(String message:new String[]{null,"symbol not found","libstdc++.so.6","libgcc_s.so.1","libm.so.6","libpthread.so.0","libc.so.6"})
            assertFalse(DaveLibraryLoader.canInstallOpus(DaveLibraryLoader.identifyMissingLibrary(message)));
    }
}
