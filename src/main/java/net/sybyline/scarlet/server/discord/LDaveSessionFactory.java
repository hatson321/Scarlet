package net.sybyline.scarlet.server.discord;

import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import net.dv8tion.jda.api.audio.dave.DaveSession;
import net.dv8tion.jda.api.audio.dave.DaveSessionFactory;
import net.sybyline.scarlet.server.discord.dave.Dave;
import net.sybyline.scarlet.util.Platform;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAVE Session Factory implementation using libdave-jvm.
 * This provides proper E2EE support for Discord voice connections.
 */
public class LDaveSessionFactory implements DaveSessionFactory {

    private static final Logger LOG = LoggerFactory.getLogger(LDaveSessionFactory.class);
    private static final LDaveSessionFactory INSTANCE = createFactory();

    private final DaveSessionFactory delegate;
    private final boolean usesBundledDave;

    private LDaveSessionFactory(DaveSessionFactory delegate, boolean usesBundledDave) {
        this.delegate = delegate;
        this.usesBundledDave = usesBundledDave;
    }

    private static LDaveSessionFactory createFactory() {
        String unsupportedReason = unsupportedPlatformReason();
        if (unsupportedReason != null) {
            LOG.warn("{}; Discord voice E2EE will be disabled", unsupportedReason);
            return null;
        }
        try {
            NativeDaveFactory.ensureAvailable();
            LOG.info("DAVE native library loaded successfully");

            NativeDaveFactory nativeFactory = new NativeDaveFactory();
            LDJDADaveSessionFactory jdaFactory = new LDJDADaveSessionFactory(nativeFactory);

            return new LDaveSessionFactory(jdaFactory, false);
        } catch (Throwable t) {
            LOG.warn("libdave-jvm session factory unavailable on {}; trying Hatson321/Scarlet Logger bundled DAVE fallback ({})",
                Platform.describe(), t.toString());
            LOG.debug("libdave-jvm session factory initialization failed", t);
            LDaveSessionFactory bundled = createBundledFactory();
            if (bundled != null) {
                return bundled;
            }
            LOG.warn("DAVE session factory unavailable on {}; Discord voice E2EE will not be available ({})",
                Platform.describe(), t.toString());
            return null;
        }
    }

    private static LDaveSessionFactory createBundledFactory() {
        try {
            short maxProtocolVersion = Dave.INSTANCE.maxSupportedProtocolVersion();
            LOG.info("Hatson321/Scarlet Logger bundled DAVE library loaded successfully (max protocol version {})",
                Short.toUnsignedInt(maxProtocolVersion));
            return new LDaveSessionFactory(
                (callbacks, userId, channelId) -> new DAudioDaveSession(callbacks, userId, channelId),
                true
            );
        } catch (Throwable t) {
            LOG.warn("Hatson321/Scarlet Logger bundled DAVE library unavailable on {} ({})",
                Platform.describe(), t.toString());
            LOG.debug("Hatson321/Scarlet Logger bundled DAVE library initialization failed", t);
            return null;
        }
    }

    private static String unsupportedPlatformReason() {
        if (Platform.is32Bit()) {
            return "32-bit JVM detected (" + Platform.describe() + "); DAVE native libraries are not bundled for this runtime. Use a 64-bit JVM to enable Discord voice E2EE";
        }
        return null;
    }

    /**
     * Get the singleton instance of the DAVE session factory.
     * @return the factory instance, or null if native library failed to load
     */
    public static LDaveSessionFactory getInstance() {
        return INSTANCE;
    }

    /**
     * Check if the DAVE native library is available.
     * @return true if DAVE is available, false otherwise
     */
    public static boolean isAvailable() {
        return INSTANCE != null;
    }

    public static void configureLoggingIfAvailable() {
        if (INSTANCE == null || !INSTANCE.usesBundledDave) {
            return;
        }
        try {
            Dave.INSTANCE.daveSetLogSinkCallbackDefault();
        } catch (Throwable t) {
            LOG.debug("Failed to configure bundled DAVE logging", t);
        }
    }

    @Override
    @NotNull
    public DaveSession createDaveSession(@NotNull net.dv8tion.jda.api.audio.dave.DaveProtocolCallbacks callbacks, long userId, long channelId) {
        return delegate.createDaveSession(callbacks, userId, channelId);
    }
}
