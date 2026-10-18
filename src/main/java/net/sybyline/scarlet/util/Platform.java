package net.sybyline.scarlet.util;

import java.util.Locale;

/**
 * Current operating system and CPU architecture.
 *
 * <p>{@link #CURRENT} is the detected OS family (Windows, Linux, macOS,
 * Solaris, or OTHER).  {@link #ARCH} is the detected CPU architecture.
 * Both are set once at class load from {@link System#getProperty}.</p>
 */
public enum Platform
{
    NT(),
    $NIX(),
    XNU(),
    SUN(),
    OTHER(),
    ;

    Platform()
    {
    }

    // =======================================================================
    // OS family
    // =======================================================================

    public static final Platform CURRENT;
    public static final boolean FORCE_HEADLESS_UI;

    static
    {
        String s = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (s.contains("win"))
            CURRENT = NT;
        else if (s.contains("linux") || s.contains("unix"))
            CURRENT = $NIX;
        else if (s.contains("mac"))
            CURRENT = XNU;
        else if (s.contains("solaris") || s.contains("sunos"))
            CURRENT = SUN;
        else
            CURRENT = OTHER;

        FORCE_HEADLESS_UI = Boolean.getBoolean("scarlet.forceHeadlessUi");
    }

    public boolean isNT()    { return this == NT; }
    public boolean is$nix() { return this == $NIX; }
    public boolean isXNU()   { return this == XNU; }
    public boolean isSun()   { return this == SUN; }
    public boolean isOther() { return this == OTHER; }
    /**
     * @return {@code true} when the JVM reports a PowerPC CPU (32- or 64-bit,
     * either endianness), e.g. a Wii running Wii-Linux or an old PowerPC Mac.
     * PowerPC is still classified as {@link Arch#OTHER}; this is a finer check.
     */
    public static boolean isPowerPC()
    {
        return ARCH_RAW.startsWith("ppc") || ARCH_RAW.startsWith("powerpc");
    }

    private static volatile Boolean PPC_EDITION;
    /**
     * @return {@code true} when this jar is the PowerPC edition, i.e. its
     * manifest carries {@code Scarlet-Edition: ppc} (added by the
     * {@code shade-ppc} build execution). Can also be forced with
     * {@code -Dscarlet.ppcEdition=true}.
     */
    public static boolean isPpcEdition()
    {
        Boolean cached = PPC_EDITION;
        if (cached != null)
            return cached;
        boolean result = Boolean.getBoolean("scarlet.ppcEdition");
        if (!result) try
        {
            java.util.Enumeration<java.net.URL> manifests = Platform.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (!result && manifests.hasMoreElements())
            {
                try (java.io.InputStream in = manifests.nextElement().openStream())
                {
                    java.util.jar.Manifest mf = new java.util.jar.Manifest(in);
                    result = "ppc".equalsIgnoreCase(mf.getMainAttributes().getValue("Scarlet-Edition"));
                }
                catch (Exception ignored) {}
            }
        }
        catch (Exception ignored) {}
        PPC_EDITION = result;
        return result;
    }

    public static boolean forceHeadlessUi()
    {
        return FORCE_HEADLESS_UI || Boolean.getBoolean("java.awt.headless");
    }

    // =======================================================================
    // CPU architecture
    // =======================================================================

    /**
     * Normalised CPU architecture classification.  Mirrors the small set
     * returned by the Python bridge so the two sides can compare cleanly.
     */
    public enum Arch
    {
        /** 64-bit Intel / AMD. */
        X86_64("x86_64"),
        /** 64-bit ARM (Apple Silicon, ARM servers, most modern phones). */
        ARM64("arm64"),
        /** 32-bit Intel.  Rare today but still shows up on embedded systems. */
        I386("i386"),
        /** 32-bit ARM.  Raspberry Pi (older), ARM embedded, etc. */
        ARM32("arm"),
        /** Unknown or exotic arch — PowerPC, RISC-V, etc. */
        OTHER("other"),
        ;

        public final String canonicalName;

        Arch(String canonicalName)
        {
            this.canonicalName = canonicalName;
        }

        @Override
        public String toString()
        {
            return canonicalName;
        }
    }

    /** Current CPU architecture, determined from {@code os.arch}. */
    public static final Arch ARCH;

    /** Raw value of {@code System.getProperty("os.arch")} for diagnostics. */
    public static final String ARCH_RAW;

    static
    {
        String raw = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        ARCH_RAW = raw;

        // JVMs report AMD64 / x86_64 / x64 for 64-bit Intel.  Normalise.
        if (raw.equals("amd64") || raw.equals("x86_64") || raw.equals("x64"))
            ARCH = Arch.X86_64;
        // aarch64 (Linux/JDK) and arm64 (macOS) both mean 64-bit ARM.
        else if (raw.equals("aarch64") || raw.equals("arm64"))
            ARCH = Arch.ARM64;
        // 32-bit x86 appears as i386, i486, i586, i686, or "x86".
        else if (raw.matches("i[3-6]86") || raw.equals("x86"))
            ARCH = Arch.I386;
        // 32-bit ARM variants.
        else if (raw.startsWith("arm"))
            ARCH = Arch.ARM32;
        else
            ARCH = Arch.OTHER;
    }

    /** @return {@code true} if the JVM is running on 64-bit ARM. */
    public static boolean isArm64()
    {
        return ARCH == Arch.ARM64;
    }

    /** @return {@code true} if the JVM is running on 64-bit Intel/AMD. */
    public static boolean isX86_64()
    {
        return ARCH == Arch.X86_64;
    }

    /** @return {@code true} if the JVM is running on any 32-bit arch. */
    public static boolean is32Bit()
    {
        return ARCH == Arch.I386 || ARCH == Arch.ARM32;
    }

    /**
     * @return a short description suitable for logs, e.g.
     *         {@code "Windows / x86_64"} or {@code "Mac / arm64"}.
     */
    public static String describe()
    {
        String os;
        switch (CURRENT)
        {
            case NT:    os = "Windows";  break;
            case $NIX:  os = "Linux";    break;
            case XNU:   os = "macOS";    break;
            case SUN:   os = "Solaris";  break;
            default:    os = "Unknown";  break;
        }
        return os + " / " + ARCH.canonicalName;
    }
}
