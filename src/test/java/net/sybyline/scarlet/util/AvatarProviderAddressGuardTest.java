package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URL;

import org.junit.jupiter.api.Test;

class AvatarProviderAddressGuardTest
{
    @Test
    void redirectCannotReachPrivateOrLocalDestination() throws Exception
    {
        URL origin = new URL("https://8.8.8.8/provider");
        for (String next : new String[] {"http://127.0.0.1/admin", "http://10.0.0.1/", "http://169.254.169.254/",
                "http://[::1]/", "http://[fd00::1]/", "file:///private", "http://user:password@8.8.8.8/"})
            assertThrows(IOException.class, () -> HttpURLInputStream.resolveRedirectUrl(origin, next, HttpURLInputStream.PUBLIC_ONLY), next);
        assertEquals("https://8.8.8.8/next", HttpURLInputStream.resolveRedirectUrl(origin, "/next", HttpURLInputStream.PUBLIC_ONLY));
    }

    @Test
    void allowedDestinationCarriesPinnedAddressWithoutOpeningASocket() throws Exception
    {
        HttpURLInputStream.PublicUrlTarget target = HttpURLInputStream.resolvePublicUrlTarget("https://8.8.8.8:443/provider");
        assertEquals("8.8.8.8", target.address.getHostAddress());
        assertEquals("8.8.8.8", target.host);
        assertEquals(443, target.port);
        assertTrue(target.secure);
    }
}
