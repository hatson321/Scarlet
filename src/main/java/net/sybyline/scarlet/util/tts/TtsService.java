package net.sybyline.scarlet.util.tts;

import java.awt.Component;
import net.sybyline.scarlet.util.I18n;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;

import net.sybyline.scarlet.Scarlet;
import net.sybyline.scarlet.ScarletDiscord;
import net.sybyline.scarlet.ScarletEventListener;
import net.sybyline.scarlet.util.Names;

public class TtsService implements Closeable
{
    private static final String MIXED_CHARACTER_ALERT_RESOURCE = "/tts/mixed-character-alert.wav";
    private static final String MIXED_CHARACTER_ALERT_FILE = "mixed-character-alert.wav";
    private static final String NUISANCE_ALERT_RESOURCE = "/tts/bl_sfx_siren_chirp.wav";
    private static final String NUISANCE_ALERT_FILE = "bl_sfx_siren_chirp.wav";

    public TtsService(File dir, ScarletEventListener eventListener, ScarletDiscord discord)
    {
        this(dir, eventListener, discord, null);
    }

    public TtsService(File dir, ScarletEventListener eventListener, ScarletDiscord discord, Component parentComponent)
    {
        Scarlet.LOG.info("Initializing TTS service...");
        this.provider = TtsProvider.select(dir.toPath(), parentComponent);
        this.eventListener = eventListener;
        this.discord = discord;
        this.parentComponent = parentComponent;
        // Bundled alert sounds live in the JAR; extract them to a scratch temp dir
        // (never the user data folder) purely so the external audio player has a path.
        File sfxDir = new File(System.getProperty("java.io.tmpdir", "."), "scarlet-sfx");
        this.mixedCharacterAlertWav = extractBundledAudio(sfxDir,
            MIXED_CHARACTER_ALERT_RESOURCE,
            MIXED_CHARACTER_ALERT_FILE);
        this.nuisanceAlertWav = extractBundledAudio(sfxDir,
            NUISANCE_ALERT_RESOURCE,
            NUISANCE_ALERT_FILE);
        Scarlet.LOG.info("TTS service initialized with provider: {}", this.provider.getClass().getSimpleName());
    }

    final TtsProvider provider;
    final ScarletEventListener eventListener;
    final ScarletDiscord discord;
    final Component parentComponent;
    final File mixedCharacterAlertWav;
    final File nuisanceAlertWav;

    /**
     * Single-threaded executor that serialises all audio playback.
     * Each TTS clip is played to completion before the next one starts,
     * preventing multiple clips from overlapping.
     */
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TTS-Playback");
        t.setDaemon(true);
        return t;
    });

    /** Returns the list of TTS voices reported by the active provider. */
    public List<String> getInstalledVoices()
    {
        return this.provider.voices();
    }

    // ── Name sanitisation ──────────────────────────────────────────────────────

    /**
     * Prepares a VRChat display name for safe TTS output.
     *
     * <p>VRChat allows display names containing characters that English TTS
     * engines silently skip or read out character-by-character ("b cyrillic
     * l l cyrillic …"): invisible control/format codepoints, private-use
     * characters, decorative symbols, and — most commonly — letters from
     * non-Latin scripts such as Cyrillic, Greek, CJK, Hangul, Thai, Hebrew,
     * Arabic, Devanagari, etc. Both failure modes leave a moderator with no
     * way to map the audio back to the actual user.
     *
     * <p>Scarlet's primary TTS use case is a moderator wearing a VR headset
     * who cannot glance at the Discord embed while an announcement plays, so
     * the spoken form should stay short and recognizable. Latin-looking
     * confusable names are read as their visual English form first. Mixed
     * script names skip script labels and use the best transliteration Scarlet
     * can make; the separate mixed-character alert tells moderators to check
     * nearby nameplates. Single-script non-Latin names keep a short script
     * prefix because that is often the clearest way to scan the lobby.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "Yamada"}          → {@code "Yamada"} (no prefix needed)</li>
     *   <li>{@code "Просто"}          → {@code "Cyrillic Prosto"}</li>
     *   <li>{@code "中文"}             → {@code "CJK Zhong Wen"}</li>
     *   <li>{@code "Αθήνα"}           → {@code "Greek Athena"}</li>
     *   <li>{@code "Yamada 山田"}      → {@code "Yamada Shan Tian"}</li>
     *   <li>{@code "Просто 中文"}      → {@code "Prosto Zhong Wen"}</li>
     * </ul>
     *
     * <p>Transliteration uses the shared {@link Names#toAscii(String)}
     * pipeline (NFKD + stroke-Latin table + {@code junidecode}). For null,
     * empty, or fully-stripped inputs (e.g. all-emoji handles), the method
     * falls back to {@code "an unnamed user"} so the announcement still
     * reads cleanly.
     *
     * @param displayName raw VRChat display name, may be null
     * @return a TTS-safe representation, never null or empty
     */
    public static String sanitizeName(String displayName)
    {
        // A name written entirely in the active UI language's own script is left as-is —
        // the matching voice pronounces it natively (e.g. a Russian voice reads Cyrillic
        // directly). Latin-based languages keep the romanise-everything path below so an
        // English/German/Spanish/etc. voice stays intelligible on foreign handles.
        java.util.Set<Character.UnicodeScript> nativeScripts = I18n.nativeScriptsForLocale(I18n.getLocale());
        if (!nativeScripts.contains(Character.UnicodeScript.LATIN)
         && Names.isEntirelyInScripts(displayName, nativeScripts))
            return displayName.trim();
        String visualAscii = Names.toVisualAscii(displayName);
        if (visualAscii != null)
            return visualAscii;
        String ascii = Names.toAscii(displayName);
        if (ascii == null)
            return I18n.tr("adv.unnamedUser");
        if (Names.hasMixedLetterScripts(displayName))
            return ascii;
        String scripts = Names.describeScripts(displayName);
        return scripts.isEmpty() ? ascii : scripts + " " + ascii;
    }

    public static boolean shouldAlertMixedCharacterName(String displayName)
    {
        return Names.hasMixedLetterScripts(displayName);
    }

    private static File extractBundledAudio(File dir, String resourcePath, String fileName)
    {
        try (InputStream in = TtsService.class.getResourceAsStream(resourcePath))
        {
            if (in == null)
            {
                Scarlet.LOG.warn("Bundled TTS audio resource not found: {}", resourcePath);
                return null;
            }

            File out = new File(dir, fileName);
            Files.createDirectories(out.toPath().getParent());
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            out.deleteOnExit();
            Scarlet.LOG.info("Extracted bundled TTS audio resource: {} -> {}", resourcePath, out);
            return out;
        }
        catch (Exception ex)
        {
            Scarlet.LOG.warn("Could not extract bundled TTS audio resource {}: {}",
                resourcePath, ex.getMessage());
            return null;
        }
    }

    // ── TTS submission ─────────────────────────────────────────────────────────

    /**
     * When true, all calls to {@link #submit} are silently dropped rather than
     * queued. Intentionally drop-not-queue: unpausing should not trigger a
     * flood of stale announcements from while TTS was paused.
     */
    private volatile boolean paused = false;

    public boolean isPaused()
    {
        return this.paused;
    }

    /**
     * Pauses or unpauses TTS output. While paused, submitted announcements are
     * dropped immediately. Existing audio that is already playing is not
     * interrupted.
     */
    public void setPaused(boolean paused)
    {
        this.paused = paused;
        Scarlet.LOG.info("TTS: {}", paused ? "paused" : "resumed");
    }

    public boolean togglePaused()
    {
        boolean next = !this.paused;
        this.setPaused(next);
        return next;
    }

    public CompletableFuture<Void> submitMixedCharacterNameJoinAlert(String marker)
    {
        return this.submitAlertThenSpeak(marker,
            I18n.tr("adv.ttsMixedCharAlert"));
    }

    /** Plays the bundled nuisance-rank alarm tone, then speaks {@code text}. */
    public CompletableFuture<Void> submitNuisanceJoinAlert(String marker, String text)
    {
        return this.submitAlertThenSpeak(marker, this.nuisanceAlertWav, text);
    }

    private CompletableFuture<Void> submitAlertThenSpeak(String marker, String text)
    {
        return this.submitAlertThenSpeak(marker, this.mixedCharacterAlertWav, text);
    }

    private CompletableFuture<Void> submitAlertThenSpeak(String marker, File alertWav, String text)
    {
        if (this.paused)
        {
            Scarlet.LOG.debug("TTS({}): Dropped mixed-character alert - TTS is paused", marker);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Path> speech = this.synthesize(marker, text);
        return CompletableFuture.runAsync(() ->
        {
            this.playPreparedAudio(marker, alertWav);
            try
            {
                Path path = speech.get();
                if (path == null)
                {
                    Scarlet.LOG.warn("TTS({}): Provider returned null path, audio generation may have failed", marker);
                    return;
                }
                this.playPreparedAudio(marker, path.toFile());
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                Scarlet.LOG.warn("TTS({}): Interrupted while waiting for mixed-character alert speech", marker);
            }
            catch (Exception ex)
            {
                Scarlet.LOG.error("TTS({}): Exception during mixed-character alert processing", marker, ex);
            }
        }, this.playbackExecutor);
    }

    public CompletableFuture<Void> submit(String marker, String text)
    {
        if (this.paused)
        {
            Scarlet.LOG.debug("TTS({}): Dropped — TTS is paused", marker);
            return CompletableFuture.completedFuture(null);
        }
        return this.synthesize(marker, text)
            .thenAcceptAsync(path ->
            {
                if (path == null)
                {
                    Scarlet.LOG.warn("TTS({}): Provider returned null path, audio generation may have failed", marker);
                    return;
                }
                this.playPreparedAudio(marker, path.toFile());
                // NOTE: The .wav file is intentionally kept on disk so that Discord and other
                // consumers have time to finish reading/streaming it. Previously, Files.deleteIfExists(path)
                // was called here immediately after submitAudio(), causing a race condition where the file
                // was gone before Discord could access it.
            }, this.playbackExecutor)
            .exceptionally(ex -> {
                Scarlet.LOG.error("TTS({}): Exception during TTS processing", marker, ex);
                return null;
            });
    }

    private CompletableFuture<Path> synthesize(String marker, String text)
    {
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        final String selectedVoice = this.eventListener.getTtsVoiceName();
        Scarlet.LOG.info("TTS: Submitting text '{}' with voice '{}' for marker {}",
            text, selectedVoice, marker);
        final String finalText = text;
        return this.provider
            .speak(finalText, selectedVoice, 1.0F, 1.0F)
            .thenCompose(path ->
            {
                // A null path means the voice failed (e.g. an Online/Natural voice that
                // cannot write to a file stream). Trigger fallback voice selection and retry
                // the same text with that voice so the user's command isn't lost.
                if (path == null)
                {
                    String fallbackVoice = this.eventListener.fallbackTtsVoice(selectedVoice);
                    if (fallbackVoice != null)
                    {
                        Scarlet.LOG.info("TTS({}): Retrying with fallback voice '{}'", marker, fallbackVoice);
                        return this.provider.speak(finalText, fallbackVoice, 1.0F, 1.0F);
                    }
                    return CompletableFuture.completedFuture(null);
                }
                return CompletableFuture.completedFuture(path);
            });
    }

    /**
     * Holds a reference to the {@link Clip} currently playing on the system
     * audio device, or {@code null} when nothing is playing. Used by
     * {@link #skip()} to stop the active clip mid-playback.
     */
    private final java.util.concurrent.atomic.AtomicReference<Clip> activeClip =
        new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * Holds the external player {@link Process} (pw-play / paplay / aplay)
     * currently rendering a clip, or {@code null} when none is running. Used by
     * {@link #skip()} to terminate playback mid-clip.
     */
    private final java.util.concurrent.atomic.AtomicReference<Process> activeProcess =
        new java.util.concurrent.atomic.AtomicReference<>(null);

    /** Ordered external players to try; the first one found on PATH wins. */
    private static final List<String> PLAYER_CANDIDATES = Arrays.asList("pw-play", "paplay", "aplay");

    /** Lazily-resolved player command prefix (e.g. {@code [paplay]} or {@code [aplay, -q]}); null once probed and none found. Guarded by {@code this}. */
    private List<String> playerPrefix;
    private boolean playerProbed;

    /** Ensures the "install an audio player" prompt is shown at most once per session. */
    private final java.util.concurrent.atomic.AtomicBoolean playerInstallPrompted =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private void playPreparedAudio(String marker, File file)
    {
        if (file == null || !file.isFile())
        {
            Scarlet.LOG.warn("TTS({}): Audio file unavailable: {}", marker, file);
            return;
        }
        if (this.eventListener != null && this.eventListener.getTtsUseDefaultAudioDevice())
        {
            // Prefer an external player (pw-play/paplay/aplay): unlike Java Sound, it
            // routes through PipeWire/PulseAudio, so it appears in the volume mixer and
            // follows the user's selected output device. Fall back to Java Sound only
            // when no such player is available (e.g. Windows/macOS).
            if (!this.playViaSubprocess(marker, file))
                playOnSystemAudio(marker, file, this.activeClip);
        }
        else if (this.discord != null)
        {
            boolean submitted = this.discord.submitAudio(file);
            Scarlet.LOG.info("TTS({}): Audio submitted to Discord, success={}, file={}", marker, submitted, file);
        }
        else
        {
            Scarlet.LOG.warn("TTS({}): No audio output route available for {}", marker, file);
        }
    }

    /**
     * Skips the clip that is currently playing on the system audio device.
     * The {@code playbackExecutor} will immediately move on to the next queued
     * item (if any). If TTS is routed to Discord instead, this is a no-op —
     * Discord manages its own audio stream.
     *
     * @return true if a clip was actually stopped, false if nothing was playing
     */
    public boolean skip()
    {
        Process proc = this.activeProcess.getAndSet(null);
        if (proc != null && proc.isAlive())
        {
            proc.destroy(); // SIGTERM → player exits → playViaSubprocess returns → executor continues
            Scarlet.LOG.info("TTS: Skipped active external player");
            return true;
        }
        Clip clip = this.activeClip.getAndSet(null);
        if (clip != null && clip.isRunning())
        {
            clip.stop(); // fires STOP event → releases semaphore → executor continues
            Scarlet.LOG.info("TTS: Skipped active clip");
            return true;
        }
        Scarlet.LOG.debug("TTS: skip() called but no clip or player was active");
        return false;
    }

    /**
     * Plays a WAV file through an external system player, trying {@code pw-play},
     * {@code paplay}, then {@code aplay} (overridable via the {@code scarlet.tts.player}
     * system property or {@code SCARLET_TTS_PLAYER} environment variable). These route
     * through PipeWire/PulseAudio, so playback shows up in the system volume mixer and
     * follows the user's selected output device — unlike Java Sound, which opens the ALSA
     * hardware device directly and bypasses the sound server.
     *
     * <p>Blocks until playback finishes so the serial {@code playbackExecutor} keeps clips
     * from overlapping. Returns {@code true} when an external player handled the request
     * (played to completion, was skipped, or errored), or {@code false} when no external
     * player is available — in which case the caller falls back to Java Sound.
     */
    private boolean playViaSubprocess(String marker, File file)
    {
        List<String> prefix = this.resolvePlayerPrefix();
        if (prefix == null || prefix.isEmpty())
        {
            Scarlet.LOG.warn("TTS({}): No external audio player (pw-play/paplay/aplay) found on PATH; "
                + "falling back to Java Sound for this clip.", marker);
            this.maybePromptAudioPlayerInstall();
            return false;
        }
        List<String> command = new ArrayList<>(prefix);
        command.add(file.getAbsolutePath());
        Scarlet.LOG.info("TTS({}): Playing via external player '{}': {}", marker, prefix.get(0), file);

        File parent = file.getParentFile();
        File playLog = new File(parent != null ? parent : new File("."), ".tts-player.log");
        Process proc = null;
        try
        {
            proc = new ProcessBuilder(command)
                .redirectOutput(playLog)
                .redirectError(playLog)
                .start();
            this.activeProcess.set(proc);

            long timeoutSeconds = estimatePlaybackTimeoutSeconds(file);
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            {
                Scarlet.LOG.warn("TTS({}): External player timed out after {}s, terminating", marker, timeoutSeconds);
                proc.destroyForcibly();
                return true;
            }
            int exit = proc.exitValue();
            if (exit == 0)
                Scarlet.LOG.info("TTS({}): External player playback complete", marker);
            else
                // A non-zero exit here is normal when skip() destroys the process mid-clip.
                Scarlet.LOG.info("TTS({}): External player exited with code {} (file={})", marker, exit, file);
            return true;
        }
        catch (IOException ex)
        {
            // Player vanished between the PATH probe and exec, or could not launch.
            Scarlet.LOG.warn("TTS({}): Could not start external player '{}', falling back to Java Sound: {}",
                marker, prefix.get(0), ex.toString());
            return false;
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            if (proc != null)
                proc.destroyForcibly();
            Scarlet.LOG.warn("TTS({}): Interrupted during external player playback", marker);
            return true;
        }
        finally
        {
            if (proc != null)
                this.activeProcess.compareAndSet(proc, null);
        }
    }

    /** Lazily resolves (once) the external player command prefix, or null if none is available. */
    private synchronized List<String> resolvePlayerPrefix()
    {
        if (!this.playerProbed)
        {
            this.playerProbed = true;
            this.playerPrefix = detectPlayerPrefix();
        }
        return this.playerPrefix;
    }

    /**
     * On desktop Linux, offers to install a CLI audio player (via the system
     * package manager) the first time playback finds none. Runs the prompt on a
     * background thread so the playback queue is not blocked, and re-arms player
     * detection if an install succeeds. No-op on Windows/macOS, where Java Sound
     * provides local playback.
     */
    private void maybePromptAudioPlayerInstall()
    {
        if (net.sybyline.scarlet.util.Platform.CURRENT != net.sybyline.scarlet.util.Platform.$NIX)
            return;
        if (!this.playerInstallPrompted.compareAndSet(false, true))
            return;
        final Component parent = this.parentComponent;
        Thread thread = new Thread(() ->
        {
            try
            {
                LinuxAudioPlayerInstallDialogs.InstallDialogResult result =
                    LinuxAudioPlayerInstallDialogs.showInstallFlowIfNeeded(parent);
                if (result == LinuxAudioPlayerInstallDialogs.InstallDialogResult.INSTALL_APPROVED_SUCCESS)
                {
                    synchronized (this)
                    {
                        this.playerProbed = false;
                        this.playerPrefix = null;
                    }
                    Scarlet.LOG.info("TTS: Audio player installed; future announcements will use it");
                }
            }
            catch (Throwable ex)
            {
                Scarlet.LOG.warn("TTS: Audio player install prompt failed", ex);
            }
        }, "TTS-Player-Install");
        thread.setDaemon(true);
        thread.start();
    }

    private static List<String> detectPlayerPrefix()
    {
        String override = System.getProperty("scarlet.tts.player");
        if (override == null || override.trim().isEmpty())
            override = System.getenv("SCARLET_TTS_PLAYER");
        if (override != null && !override.trim().isEmpty())
        {
            List<String> prefix = new ArrayList<>();
            for (String part : override.trim().split("\\s+"))
                if (!part.isEmpty())
                    prefix.add(part);
            Scarlet.LOG.info("TTS: Using configured audio player override: {}", prefix);
            return prefix;
        }
        for (String exe : PLAYER_CANDIDATES)
        {
            if (isExecutableOnPath(exe))
            {
                List<String> prefix = new ArrayList<>();
                prefix.add(exe);
                if ("aplay".equals(exe))
                    prefix.add("-q");
                Scarlet.LOG.info("TTS: Selected system audio player '{}' for local TTS playback", exe);
                return prefix;
            }
        }
        return null;
    }

    private static boolean isExecutableOnPath(String exe)
    {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty())
            return false;
        for (String dir : path.split(File.pathSeparator))
        {
            if (dir.isEmpty())
                continue;
            File candidate = new File(dir, exe);
            if (candidate.isFile() && candidate.canExecute())
                return true;
        }
        return false;
    }

    /** Estimates a generous playback timeout from the WAV header, defaulting to 60s on any error. */
    private static long estimatePlaybackTimeoutSeconds(File file)
    {
        try
        {
            AudioFileFormat aff = AudioSystem.getAudioFileFormat(file);
            long frames = aff.getFrameLength();
            float frameRate = aff.getFormat().getFrameRate();
            if (frames > 0 && frameRate > 0)
                return Math.max(30L, (long) (frames / frameRate) + 10L);
        }
        catch (Exception ignored)
        {
            // fall through to default
        }
        return 60L;
    }

    /**
     * Plays a WAV file through the system default audio output device using javax.sound.sampled.
     * Blocks the calling thread until playback is complete, so the serial playbackExecutor
     * prevents any two clips from overlapping.
     */
    private static void playOnSystemAudio(String marker, File file,
        java.util.concurrent.atomic.AtomicReference<Clip> activeClip)
    {
        Scarlet.LOG.info("TTS({}): Playing on system audio device: {}", marker, file);
        try
        {
            // Open the AudioInputStream first, then open the Clip with it.
            // Clip.open() fully buffers the audio into memory, so the stream
            // can be safely closed immediately afterwards.
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            Clip clip = AudioSystem.getClip();
            try
            {
                clip.open(ais);
            }
            finally
            {
                // Safe to close now — Clip has already copied the data
                ais.close();
            }

            // Use a Semaphore to block until the STOP event fires, ensuring
            // this method does not return until the clip has finished playing.
            Semaphore done = new Semaphore(0);
            clip.addLineListener(event ->
            {
                if (event.getType() == LineEvent.Type.STOP)
                {
                    activeClip.compareAndSet(clip, null); // clear only if still ours
                    clip.close();
                    Scarlet.LOG.info("TTS({}): System audio playback complete", marker);
                    done.release();
                }
            });
            activeClip.set(clip);
            clip.start();

            // Block until playback finishes (or give up after a generous timeout).
            long timeoutSeconds = Math.max(30L, (clip.getMicrosecondLength() / 1_000_000L) + 10L);
            if (!done.tryAcquire(timeoutSeconds, TimeUnit.SECONDS))
            {
                Scarlet.LOG.warn("TTS({}): Playback timed out after {}s, forcing clip close", marker, timeoutSeconds);
                clip.close();
            }
        }
        catch (Exception ex)
        {
            Scarlet.LOG.error("TTS({}): Exception playing on system audio device", marker, ex);
        }
    }

    @Override
    public void close()
    {
        this.provider.close();
        this.playbackExecutor.shutdown();
        try
        {
            if (!this.playbackExecutor.awaitTermination(5, TimeUnit.SECONDS))
                this.playbackExecutor.shutdownNow();
        }
        catch (InterruptedException ex)
        {
            this.playbackExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
