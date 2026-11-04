package net.sybyline.scarlet.util.tts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.Scarlet;
import net.sybyline.scarlet.util.BoundedProcess;

/**
 * Linux TTS provider backed by locally installed command-line engines.
 *
 * <p>The first Scarlet Linux provider only drove {@code espeak}. This broader
 * implementation keeps eSpeak compatibility, but also discovers Flite, Mimic,
 * and Festival/text2wave when they are installed. Voice ids are prefixed with
 * the engine name so users can see which synthesizer they are choosing.
 */
public class LinuxCommandTtsProvider implements TtsProvider
{

    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/TTS/Linux");
    private static final int SYNTH_TIMEOUT_SECONDS = 60;

    private final Path dir;
    private final Map<String, Voice> voicesById = new LinkedHashMap<>();
    private final List<String> voices = new ArrayList<>();
    private final AtomicReference<String> lastVoice = new AtomicReference<>();

    public LinuxCommandTtsProvider(Path dir) throws IOException, InterruptedException
    {
        this.dir = TtsProviderUtil.checkDir(dir);
        this.initEngines();
        if (this.voices.isEmpty())
            throw new IOException("No supported Linux TTS command was found on PATH or in the standard Linuxbrew location");
        this.lastVoice.set(this.voices.get(0));
        LOG.info("Linux TTS voices available: {}", this.voices);
    }

    public static boolean hasAnyEngineInstalled()
    {
        return isCommandAvailable("flite")
            || isCommandAvailable("pico2wave")
            || isCommandAvailable("espeak-ng")
            || isCommandAvailable("espeak");
    }

    public static boolean isCommandAvailable(String command)
    {
        return LinuxTtsExecutables.find(command) != null;
    }

    static boolean isCommandAvailable(String command, String searchPath)
    {
        return LinuxTtsExecutables.find(command, searchPath) != null;
    }

    private void initEngines() throws IOException, InterruptedException
    {
        this.registerFliteFamily("flite", "Flite", false);
        this.registerPico();
        this.registerEspeak("espeak-ng", "eSpeak NG");
        this.registerEspeak("espeak", "eSpeak");
    }

    private void registerEspeak(String command, String displayName) throws IOException, InterruptedException
    {
        String executable = LinuxTtsExecutables.find(command);
        if (executable == null)
            return;

        Engine engine = new EspeakEngine(command, displayName, executable);
        List<String> voiceNames = listEspeakVoices(executable);
        if (voiceNames.isEmpty())
            voiceNames.add("default");
        this.addVoices(engine, voiceNames);
    }

    private void registerFliteFamily(String command, String displayName, boolean mimic) throws IOException, InterruptedException
    {
        String executable = LinuxTtsExecutables.find(command);
        if (executable == null)
            return;

        Engine engine = new FliteFamilyEngine(command, displayName, executable, mimic);
        List<String> voiceNames = listFliteVoices(executable);
        if (voiceNames.isEmpty())
            voiceNames.add("default");
        // A flite-family binary can be installed but non-functional \u2014 e.g. a Mimic 3 binary
        // rejects the Mimic 1 flags Scarlet passes and exits non-zero. Probe once so a broken engine
        // drops out of the voice list instead of offering voices that always fail at speak-time.
        if (!engineProducesAudio(engine, voiceNames.get(0)))
        {
            LOG.info("Linux TTS engine {} is installed but produced no audio on a probe; skipping its voices.", displayName);
            return;
        }
        this.addVoices(engine, voiceNames);
    }

    /**
     * Synthesizes a tiny sample to a temp file to verify an engine actually works before its voices
     * are offered. Returns true only on a zero exit code and a non-empty output file. Best-effort;
     * any failure counts as "doesn't work" so the engine is skipped rather than trusted.
     */
    private boolean engineProducesAudio(Engine engine, String voiceName) throws InterruptedException
    {
        Path wav = null, log = null;
        try
        {
            wav = Files.createTempFile("scarlet-tts-probe", ".wav");
            log = Files.createTempFile("scarlet-tts-probe", ".log");
            int code = engine.synthesize(new Voice(engine.key + "/" + voiceName, voiceName, engine), "test", wav, log.toFile(), 1.0F, 1.0F);
            return code == 0 && Files.size(wav) > 0L;
        }
        catch (InterruptedException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            return false;
        }
        finally
        {
            try { if (wav != null) Files.deleteIfExists(wav); } catch (Exception ignore) {}
            try { if (log != null) Files.deleteIfExists(log); } catch (Exception ignore) {}
        }
    }

    private void registerPico()
    {
        String executable = LinuxTtsExecutables.find("pico2wave");
        if (executable == null)
            return;

        Engine engine = new PicoEngine(executable);
        this.addVoices(engine, Arrays.asList("en-US", "en-GB", "de-DE", "es-ES", "fr-FR", "it-IT"));
    }

    private void addVoices(Engine engine, List<String> voiceNames)
    {
        for (String voiceName : voiceNames)
        {
            if (voiceName == null)
                continue;
            voiceName = voiceName.trim();
            if (voiceName.isEmpty())
                continue;
            String id = engine.key + "/" + voiceName;
            if (this.voicesById.containsKey(id))
                continue;
            Voice voice = new Voice(id, voiceName, engine);
            this.voicesById.put(id, voice);
            this.voices.add(id);
        }
        LOG.info("Registered Linux TTS engine {} with {} voice(s)", engine.displayName, Integer.valueOf(voiceNames.size()));
    }

    @Override
    public void close()
    {
    }

    @Override
    public List<String> voices()
    {
        return Collections.unmodifiableList(this.voices);
    }

    @Override
    public CompletableFuture<Path> speak(String text, String voiceId, float volume, float speed)
    {
        return CompletableFuture.supplyAsync(() ->
        {
            Voice voice = this.selectVoice(voiceId);
            if (voice == null)
            {
                LOG.warn("Linux TTS speak request skipped because no voice is available.");
                return null;
            }

            Path outWav = this.dir.resolve(TtsProviderUtil.newRequestName());
            File outLog = new File(outWav.toString() + ".log");
            try
            {
                Files.deleteIfExists(outWav);
                int exit = voice.engine.synthesize(voice, text, outWav, outLog, volume, speed);
                if (exit != 0)
                {
                    LOG.warn("Linux TTS engine {} failed with exit code {} for voice {}. Log: {}",
                        voice.engine.displayName, Integer.valueOf(exit), voice.id, outLog);
                    return null;
                }
                if (!Files.isRegularFile(outWav) || Files.size(outWav) <= 0L)
                {
                    LOG.warn("Linux TTS engine {} reported success but did not create audio for voice {}",
                        voice.engine.displayName, voice.id);
                    return null;
                }
                LOG.info("Linux TTS generated {} using {}", outWav, voice.id);
                return outWav;
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                LOG.debug("Linux TTS request interrupted; cleanup was requested for its owned child.");
                return null;
            }
            catch (Exception ex)
            {
                LOG.warn("Linux TTS engine {} failed for voice {}", voice.engine.displayName, voice.id, ex);
                return null;
            }
        });
    }

    private Voice selectVoice(String requestedVoice)
    {
        String requested = requestedVoice == null ? "" : requestedVoice.trim();
        Voice voice = requested.isEmpty() ? null : this.voicesById.get(requested);
        if (voice == null && !requested.isEmpty())
        {
            for (Voice candidate : this.voicesById.values())
            {
                if (requested.equals(candidate.voiceName)
                    || requested.equals(candidate.engine.key + ":" + candidate.voiceName))
                {
                    voice = candidate;
                    break;
                }
            }
        }
        if (voice == null)
            voice = this.voicesById.get(this.lastVoice.get());
        if (voice == null && !this.voices.isEmpty())
            voice = this.voicesById.get(this.voices.get(0));
        if (voice != null)
            this.lastVoice.set(voice.id);
        if (!requested.isEmpty() && voice != null && !requested.equals(voice.id) && !requested.equals(voice.voiceName))
            Scarlet.LOG.warn("Configured Linux TTS voice `{}` is not installed. Falling back to `{}`.", requested, voice.id);
        return voice;
    }

    private static List<String> listEspeakVoices(String command) throws IOException, InterruptedException
    {
        return parseEspeakVoices(runAndCollect(Arrays.asList(command, "--voices")));
    }

    static List<String> parseEspeakVoices(List<String> lines)
    {
        List<String> tokens = new ArrayList<>();
        for (String line : lines)
        {
            if (line == null)
                continue;
            String[] columns = line.trim().split("\\s+");
            if (columns.length >= 2 && columns[0].matches("[0-9]+"))
                tokens.add(columns[1]);
        }
        return cleanVoiceTokens(tokens);
    }

    private static List<String> listFliteVoices(String command) throws IOException, InterruptedException
    {
        List<String> out = runAndCollect(Arrays.asList(command, "-lv"));
        List<String> voices = new ArrayList<>();
        for (String line : out)
        {
            String normalized = line.replace(':', ' ');
            String[] parts = normalized.split("\\s+");
            for (String part : parts)
            {
                part = part.trim();
                if (part.isEmpty())
                    continue;
                String lower = part.toLowerCase();
                if ("voices".equals(lower) || "available".equals(lower) || "voice".equals(lower)
                    || "list".equals(lower) || "internal".equals(lower))
                    continue;
                if (isSimpleVoiceToken(part))
                    voices.add(part);
            }
        }
        return unique(voices);
    }

    private static List<String> cleanVoiceTokens(List<String> tokens)
    {
        List<String> voices = new ArrayList<>();
        for (String token : tokens)
        {
            token = token == null ? "" : token.trim();
            if (isSimpleVoiceToken(token))
                voices.add(token);
        }
        return unique(voices);
    }

    private static boolean isSimpleVoiceToken(String token)
    {
        return token.matches("[A-Za-z0-9_.+@-]+");
    }

    private static List<String> unique(List<String> values)
    {
        List<String> result = new ArrayList<>();
        for (String value : values)
            if (!result.contains(value))
                result.add(value);
        return result;
    }

    private static List<String> runAndCollect(List<String> command) throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        BoundedProcess.Result result = BoundedProcess.await(process, 10_000L, 1024 * 1024, () -> false, null);
        return result.exitCode == 0 && result.terminated
            ? java.util.Arrays.asList(result.output.split("\\R")) : Collections.emptyList();
    }

    private static int waitFor(Process process, File logFile) throws IOException, InterruptedException
    {
        return waitFor(process, logFile, SYNTH_TIMEOUT_SECONDS * 1000L);
    }

    static int waitFor(Process process, File logFile, long timeoutMillis) throws IOException, InterruptedException
    {
        BoundedProcess.Result result = BoundedProcess.await(process, timeoutMillis, 1024 * 1024, () -> false, null);
        Files.write(logFile.toPath(), result.output.getBytes(StandardCharsets.UTF_8));
        if (!result.terminated)
        {
            LOG.warn("Linux TTS child termination could not be confirmed. Log: {}", logFile);
            return -1;
        }
        if (result.exitCode == BoundedProcess.TIMED_OUT)
            LOG.warn("Linux TTS process timed out after {}ms. Log: {}", Long.valueOf(timeoutMillis), logFile);
        return result.exitCode;
    }

    private static int amplitude(float volume)
    {
        if (Float.isNaN(volume))
            volume = 1.0F;
        volume = Math.max(0.0F, Math.min(1.0F, volume));
        return Math.max(0, Math.min(200, (int)(volume * 100.0F)));
    }

    private static int wordsPerMinute(float speed)
    {
        if (Float.isNaN(speed))
            speed = 1.0F;
        speed = Math.max(0.5F, Math.min(2.0F, speed));
        return Math.max(80, Math.min(450, (int)(175.0F * speed)));
    }

    private static final class Voice
    {
        final String id;
        final String voiceName;
        final Engine engine;

        Voice(String id, String voiceName, Engine engine)
        {
            this.id = id;
            this.voiceName = voiceName;
            this.engine = engine;
        }
    }

    private abstract static class Engine
    {
        final String key;
        final String displayName;
        final String command;

        Engine(String key, String displayName, String command)
        {
            this.key = key;
            this.displayName = displayName;
            this.command = command;
        }

        abstract int synthesize(Voice voice, String text, Path outWav, File outLog, float volume, float speed) throws Exception;
    }

    private static final class EspeakEngine extends Engine
    {
        EspeakEngine(String key, String displayName, String command)
        {
            super(key, displayName, command);
        }

        @Override
        int synthesize(Voice voice, String text, Path outWav, File outLog, float volume, float speed) throws Exception
        {
            List<String> cmd = new ArrayList<>();
            cmd.add(this.command);
            cmd.add("-a");
            cmd.add(Integer.toString(amplitude(volume)));
            cmd.add("-s");
            cmd.add(Integer.toString(wordsPerMinute(speed)));
            if (!"default".equals(voice.voiceName))
            {
                cmd.add("-v");
                cmd.add(voice.voiceName);
            }
            cmd.add("-w");
            cmd.add(outWav.toString());
            cmd.add(text);
            Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
            return waitFor(process, outLog);
        }
    }

    private static final class FliteFamilyEngine extends Engine
    {
        final boolean mimic;

        FliteFamilyEngine(String key, String displayName, String command, boolean mimic)
        {
            super(key, displayName, command);
            this.mimic = mimic;
        }

        @Override
        int synthesize(Voice voice, String text, Path outWav, File outLog, float volume, float speed) throws Exception
        {
            List<String> cmd = new ArrayList<>();
            cmd.add(this.command);
            cmd.add("-t");
            cmd.add(text);
            cmd.add("-o");
            cmd.add(outWav.toString());
            if (!"default".equals(voice.voiceName))
            {
                cmd.add("-voice");
                cmd.add(voice.voiceName);
            }
            if (this.mimic && speed > 0.0F && speed != 1.0F)
            {
                float stretch = 1.0F / Math.max(0.5F, Math.min(2.0F, speed));
                cmd.add("--setf");
                cmd.add("duration_stretch=" + Float.toString(stretch));
            }
            Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
            return waitFor(process, outLog);
        }
    }

    private static final class PicoEngine extends Engine
    {
        PicoEngine(String command)
        {
            super("pico2wave", "Pico TTS", command);
        }

        @Override
        int synthesize(Voice voice, String text, Path outWav, File outLog, float volume, float speed) throws Exception
        {
            List<String> cmd = new ArrayList<>();
            cmd.add(this.command);
            cmd.add("-l");
            cmd.add(voice.voiceName);
            cmd.add("-w");
            cmd.add(outWav.toString());
            cmd.add(text);
            Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
            return waitFor(process, outLog);
        }
    }
}
