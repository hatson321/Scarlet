package net.sybyline.scarlet;

import net.sybyline.scarlet.util.ConsoleUtf8;
import net.sybyline.scarlet.util.MavenDepsLoader;
import net.sybyline.scarlet.util.Platform;

public abstract class Main
{

    static
    {
        if (Platform.forceHeadlessUi() && System.getProperty("java.awt.headless") == null)
        {
            System.setProperty("java.awt.headless", "true");
            System.err.println("Headless UI mode requested; forcing java.awt.headless=true");
        }
        MavenDepsLoader.init();
        // After dependencies (incl. JNA) are loaded, switch console + stdout/stderr
        // to UTF-8 so Unicode display names don't print as "?".
        ConsoleUtf8.install();
    }

    public static void main(String[] args) throws Throwable
    {
        Scarlet.main(args);
    }

    private Main()
    {
        throw new UnsupportedOperationException();
    }

}
