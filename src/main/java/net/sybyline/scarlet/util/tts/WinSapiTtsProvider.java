package net.sybyline.scarlet.util.tts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid.CLSID;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Guid.IID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Windows SAPI-backed TTS provider.
 *
 * <p>This implementation intentionally favors broad compatibility:
 * it enumerates voices from both legacy and modern speech categories,
 * tolerates partially broken categories, and falls back to older WAV
 * output formats when newer ones are rejected by old SAPI installs.</p>
 */
public class WinSapiTtsProvider implements TtsProvider
{

    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/TTS/WinSAPI");
    private static final int SPERR_NOT_FOUND = 0x80045002;

    public static final String NaturalVoiceSAPIAdapter_URL = "https://github.com/gexgd0419/NaturalVoiceSAPIAdapter";

    public WinSapiTtsProvider(Path dir) throws InterruptedException, ExecutionException
    {
        this.dir = TtsProviderUtil.checkDir(dir);
        net.sybyline.scarlet.util.BoundedInitialization.run(this.executor, () ->
        {
            this.comInitState = initializeComForLegacyWindows();
            this.synth = new Invoker(CLSID_SpVoice, IID_ISpVoice);
            this.initVoices();
        }, 10_000L);
    }

    private final Path dir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(TtsThread::new);
    private final Map<String, Invoker> voiceById = new LinkedHashMap<>();
    private final List<String> voices = new ArrayList<>();
    private volatile Invoker synth = null;
    private ComInitState comInitState;
    private volatile String defaultVoiceId = null;

    private void initVoices()
    {
        PointerByReference ppv = new PointerByReference();
        try (Invoker spOTC = new Invoker(CLSID_SpObjectTokenCategory, IID_ISpObjectTokenCategory))
        {
            for (WString category : VOICE_CATEGORIES)
                this.enumerateVoiceCategory(spOTC, category, ppv);
        }
        if (this.voices.isEmpty())
        {
            LOG.warn("No Windows TTS voices were discovered through SAPI. Hatson321/Scarlet Logger will keep TTS enabled, but announcements cannot speak until a compatible voice is installed.");
        }
        else
        {
            this.defaultVoiceId = this.voices.get(0);
            LOG.info("Windows TTS voices available ({}): {}", Integer.toUnsignedString(this.voices.size()), this.voices);
        }
    }
    private void enumerateVoiceCategory(Invoker categoryObj, WString category, PointerByReference ppv)
    {
        try
        {
            categoryObj.invokeCheckHRESULT(ISpObjectTokenCategory_SetId, categoryObj, category, (short)0);
            categoryObj.invokeCheckHRESULT(ISpObjectTokenCategory_EnumTokens, categoryObj, null, null, ppv);
            try (Invoker enumTokens = new Invoker(ppv.getValue()))
            {
                IntByReference fetched = new IntByReference();
                while (WinNT.S_OK.equals(enumTokens.invokeHRESULT(IEnumSpObjectTokens_Next, enumTokens, 1, ppv, fetched)) && fetched.getValue() == 1)
                    this.registerVoice(category.toString(), new Invoker(ppv.getValue()), ppv);
            }
        }
        catch (Exception ex)
        {
            if (isMissingVoiceCategory(ex))
                LOG.trace("Windows TTS voice category `{}` is not present on this system", category);
            else
                LOG.debug("Unable to enumerate Windows TTS voices from category `{}`", category, ex);
        }
    }
    private void registerVoice(String category, Invoker token, PointerByReference ppv)
    {
        try
        {
            String id = this.readTokenId(token, ppv);
            String label = this.readVoiceLabel(token, ppv, id);
            if (label == null)
                label = id;
            if (label != null)
                label = label.trim();
            if (label == null || label.isEmpty())
            {
                token.close();
                return;
            }
            if (this.voiceById.putIfAbsent(label, token) != null)
            {
                token.close();
                return;
            }
            this.voices.add(label);
            LOG.debug("Registered Windows TTS voice `{}` from `{}`", label, category);
            if (label.contains("Online") || label.contains("(Natural)"))
            {
                LOG.info("TTS: Voice '{}' appears to be an Online/Natural voice. "
                    + "It will work if NaturalVoiceSAPIAdapter is installed (see {}), "
                    + "otherwise Hatson321/Scarlet Logger will automatically fall back to a Desktop voice if it fails.",
                    label, NaturalVoiceSAPIAdapter_URL);
            }
        }
        catch (Exception ex)
        {
            token.close();
            LOG.debug("Failed to inspect a Windows TTS voice token from `{}`", category, ex);
        }
    }
    private String readVoiceLabel(Invoker token, PointerByReference ppv, String fallbackId)
    {
        String label = this.tryReadTokenString(token, Description, ppv);
        if (label != null && !label.trim().isEmpty())
            return label;
        label = this.tryReadTokenString(token, Attributes_Name, ppv);
        if (label != null && !label.trim().isEmpty())
            return label;
        return fallbackId;
    }
    private String readTokenId(Invoker token, PointerByReference ppv)
    {
        token.invokeCheckHRESULT(ISpObjectToken_GetId, token, ppv);
        Pointer value = ppv.getValue();
        return value == null ? null : value.getWideString(0L);
    }
    private String tryReadTokenString(Invoker token, WString key, PointerByReference ppv)
    {
        try
        {
            token.invokeCheckHRESULT(ISpObjectToken_GetStringValue, token, key, ppv);
            Pointer value = ppv.getValue();
            return value == null ? null : value.getWideString(0L);
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    @Override
    public List<String> voices()
    {
        return Collections.unmodifiableList(this.voices);
    }

    @Override
    public CompletableFuture<Path> speak(String text, String voiceId, float volume, float speed)
    {
        return CompletableFuture.supplyAsync(() -> {
            Invoker voice = this.synth;
            if (voice == null)
            {
                LOG.warn("Windows TTS speak request was skipped because the SAPI voice engine is not initialized.");
                return null;
            }
            Path path = this.dir.resolve(TtsProviderUtil.newRequestName());
            Invoker token = this.selectVoiceToken(voiceId);
            String effectiveVoice = this.describeVoice(token, voiceId);
            try
            {
                if (token != null)
                    voice.invokeCheckHRESULT(ISpVoice_SetVoice, voice, token);
                voice.invokeCheckHRESULT(ISpVoice_SetVolume, voice, volume(volume));
                voice.invokeCheckHRESULT(ISpVoice_SetRate, voice, speed(speed));
                this.speakToFileWithCompatibleFormat(voice, effectiveVoice, text, path);
                return path;
            }
            catch (com.sun.jna.platform.win32.COM.COMException comEx)
            {
                WinNT.HRESULT hresult = comEx.getHresult();
                int hr = hresult != null ? hresult.intValue() : 0;
                if (hr == 0x80004005 || hr == (int)0x80004005L)
                {
                    LOG.warn("TTS voice '{}' is an Online/Natural voice that cannot write to a file stream "
                        + "(HRESULT: 0x{}) - returning null so caller can switch to a fallback voice. "
                        + "See: " + NaturalVoiceSAPIAdapter_URL,
                        effectiveVoice, Integer.toHexString(hr).toUpperCase());
                    return null;
                }
                throw comEx;
            }
            finally
            {
                voice.invokeCheckHRESULT(ISpVoice_SetOutput, voice, null, 1);
            }
        }, this.executor);
    }
    private Invoker selectVoiceToken(String requestedVoice)
    {
        Invoker token = requestedVoice == null ? null : this.voiceById.get(requestedVoice);
        if (token != null)
            return token;
        if (requestedVoice != null && !requestedVoice.trim().isEmpty())
            LOG.warn("Configured TTS voice `{}` is not currently installed. Falling back to the first discovered Windows voice.", requestedVoice);
        return this.defaultVoiceId == null ? null : this.voiceById.get(this.defaultVoiceId);
    }
    private String describeVoice(Invoker token, String requestedVoice)
    {
        if (token != null)
            for (Map.Entry<String, Invoker> entry : this.voiceById.entrySet())
                if (entry.getValue() == token)
                    return entry.getKey();
        return requestedVoice == null || requestedVoice.trim().isEmpty() ? "<default>" : requestedVoice;
    }
    private void speakToFileWithCompatibleFormat(Invoker voice, String effectiveVoice, String text, Path path)
    {
        com.sun.jna.platform.win32.COM.COMException lastException = null;
        for (WaveFormatEx format : WAVEFORMATS_TRY)
        {
            try (Invoker stream = new Invoker(CLSID_SpStream, IID_ISpStream))
            {
                stream.invokeCheckHRESULT(ISpStream_BindToFile, stream,
                    new WString(path.toString()),
                    0x3,
                    SPDFID_WaveFormatEx,
                    format,
                    0L);
                try
                {
                    voice.invokeCheckHRESULT(ISpVoice_SetOutput, voice, stream, 1);
                    voice.invokeCheckHRESULT(ISpVoice_Speak, voice, new WString(text), 0x0010, new IntByReference());
                    return;
                }
                finally
                {
                    stream.invokeCheckHRESULT(ISpStream_Close, stream);
                }
            }
            catch (com.sun.jna.platform.win32.COM.COMException comEx)
            {
                lastException = comEx;
                LOG.debug("Windows TTS voice `{}` could not write using format {} Hz / {} bit mono", effectiveVoice,
                    Integer.toUnsignedString(format.nSamplesPerSec), Integer.toUnsignedString(format.wBitsPerSample & 0xFFFF), comEx);
            }
        }
        if (lastException != null)
            throw lastException;
    }
    private static int speed(float speed)
    {
        int rate = Math.round((speed - 1.0F) * 10);
        return Math.max(-10, Math.min(rate, 10));
    }
    private static short volume(float volume)
    {
        return (short)(Math.max(0, Math.min(Math.round(volume * 100), 100)) & 0x0000_FFFF);
    }

    @Override
    public void close()
    {
        this.executor.shutdown();
        try
        {
            if (this.executor.awaitTermination(10_000L, TimeUnit.MILLISECONDS))
                return;
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
        this.executor.shutdownNow();
    }
    private void releaseVoices()
    {
        // Release each token independently so a broken native voice cannot skip the rest.
        this.voiceById.values().forEach(token -> {
            try { token.close(); }
            catch (RuntimeException ex) { LOG.warn("Unable to release a Windows TTS voice token", ex); }
        });
        this.voiceById.clear();
    }

    private class TtsThread extends Thread
    {
        TtsThread(Runnable target)
        {
            super(target, "Scarlet-WinSapi-TTS");
            this.setDaemon(true);
        }
        @Override
        public void run()
        {
            try
            {
                super.run();
            }
            finally
            {
                try
                {
                    WinSapiTtsProvider.this.releaseVoices();
                    if (WinSapiTtsProvider.this.synth != null) WinSapiTtsProvider.this.synth.close();
                }
                finally
                {
                    WinSapiTtsProvider.this.synth = null;
                    if (WinSapiTtsProvider.this.comInitState != null && WinSapiTtsProvider.this.comInitState.shouldUninitialize)
                        Ole32.INSTANCE.CoUninitialize();
                }
            }
        }
    }
    private static ComInitState initializeComForLegacyWindows()
    {
        HRESULT hr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
        if (isSuccess(hr))
            return new ComInitState(true, "MTA");
        if (isChangedMode(hr))
            return new ComInitState(false, "existing");
        LOG.debug("CoInitializeEx(MTA) failed with HRESULT 0x{}; trying STA fallback", Integer.toHexString(hr == null ? 0 : hr.intValue()).toUpperCase());
        hr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED);
        if (isSuccess(hr))
            return new ComInitState(true, "STA");
        if (isChangedMode(hr))
            return new ComInitState(false, "existing");
        COMUtils.checkRC(hr);
        return new ComInitState(false, "unknown");
    }
    private static boolean isSuccess(HRESULT hr)
    {
        return hr != null && hr.intValue() >= 0;
    }
    private static boolean isChangedMode(HRESULT hr)
    {
        return hr != null && hr.intValue() == (int)0x80010106L;
    }
    private static boolean isMissingVoiceCategory(Throwable ex)
    {
        for (Throwable t = ex; t != null; t = t.getCause())
            if (t instanceof com.sun.jna.platform.win32.COM.COMException)
            {
                HRESULT hr = ((com.sun.jna.platform.win32.COM.COMException)t).getHresult();
                if (hr != null && hr.intValue() == (int)SPERR_NOT_FOUND)
                    return true;
            }
        return false;
    }

    private static final CLSID
        CLSID_SpObjectTokenCategory = new CLSID("{A910187F-0C7A-45AC-92CC-59EDAFB77B53}"),
        CLSID_SpStream = new CLSID("{715D9C59-4442-11D2-9605-00C04F8EE628}"),
        CLSID_SpVoice = new CLSID("{96749377-3391-11D2-9EE3-00C04F797396}");
    private static final IID
        IID_ISpObjectTokenCategory = new IID("{2D3D3845-39AF-4850-BBF9-40B49780011D}"),
        IID_ISpStream = new IID("{12E3CCA9-7518-44C5-A5E7-BA5A79CB929E}"),
        IID_ISpVoice = new IID("{6C44DF74-72B9-4992-A1EC-EF996E0422D4}");
    private static final int
        IEnumSpObjectTokens_Next = 3,
        ISpObjectTokenCategory_SetId = 15,
        ISpObjectTokenCategory_EnumTokens = 18,
        ISpObjectToken_GetStringValue = 6,
        ISpObjectToken_GetId = 16,
        ISpStream_BindToFile = 17,
        ISpStream_Close = 18,
        ISpVoice_SetOutput = 13,
        ISpVoice_SetVoice = 18,
        ISpVoice_Speak = 20,
        ISpVoice_SetRate = 28,
        ISpVoice_SetVolume = 30;
    private static final WString
        Description = new WString(""),
        Attributes_Name = new WString("Name");
    private static final WString[] VOICE_CATEGORIES =
    {
        new WString("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices"),
        new WString("HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Speech\\Voices"),
        new WString("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech_OneCore\\Voices"),
        new WString("HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Speech_OneCore\\Voices"),
    };
    private static final GUID.ByReference
        SPDFID_WaveFormatEx = new GUID.ByReference(new GUID("{c31adbae-527f-4ff5-a230-f62bb61ff70c}"));
    private static final WaveFormatEx
        WAVEFORMATEX_22kHz16BitMono = SpConvertStreamFormatEnum(ESpFormat.SPSF_22kHz16BitMono),
        WAVEFORMATEX_11kHz16BitMono = SpConvertStreamFormatEnum(ESpFormat.SPSF_11kHz16BitMono),
        WAVEFORMATEX_8kHz16BitMono = SpConvertStreamFormatEnum(ESpFormat.SPSF_8kHz16BitMono),
        WAVEFORMATEX_8kHz8BitMono = SpConvertStreamFormatEnum(ESpFormat.SPSF_8kHz8BitMono);
    private static final WaveFormatEx[] WAVEFORMATS_TRY =
    {
        WAVEFORMATEX_22kHz16BitMono,
        WAVEFORMATEX_11kHz16BitMono,
        WAVEFORMATEX_8kHz16BitMono,
        WAVEFORMATEX_8kHz8BitMono,
    };

    private static final class ComInitState
    {
        final boolean shouldUninitialize;
        final String mode;
        ComInitState(boolean shouldUninitialize, String mode)
        {
            this.shouldUninitialize = shouldUninitialize;
            this.mode = mode;
        }
    }

    private static class Invoker extends Unknown implements AutoCloseable
    {
        static Pointer create(CLSID clsid, IID iid)
        {
            PointerByReference ppv = new PointerByReference();
            COMUtils.checkRC(Ole32.INSTANCE.CoCreateInstance(clsid, null, WTypes.CLSCTX_ALL, iid, ppv));
            return ppv.getValue();
        }
        Invoker(CLSID clsid, IID iid)
        {
            this(create(clsid, iid));
        }
        Invoker(Pointer pv)
        {
            super(pv);
        }
        void invokeCheckHRESULT(int vtidx, Object... args)
        {
            COMUtils.checkRC(this.invokeHRESULT(vtidx, args));
        }
        HRESULT invokeHRESULT(int vtidx, Object... args)
        {
            Pointer ptr = this.getPointer(),
                    vtptr = ptr.getPointer(0);
            Function func = Function.getFunction(vtptr.getPointer(Native.POINTER_SIZE * vtidx), Function.C_CONVENTION);
            if (args[0] != this)
                throw new IllegalArgumentException("args[0] != this");
            args[0] = ptr;
            for (int i = 1; i < args.length; i++)
                if (args[i] instanceof Invoker)
                    args[i] = ((Invoker)args[i]).getPointer();
            return (HRESULT)func.invoke(HRESULT.class, args);
        }
        public void close()
        {
            this.Release();
        }
    }

    public interface ESpFormat
    {
        int SPSF_Default = -1,
            SPSF_NoAssignedFormat = 0,
            SPSF_Text = (SPSF_NoAssignedFormat + 1),
            SPSF_NonStandardFormat = (SPSF_Text + 1),
            SPSF_ExtendedAudioFormat = (SPSF_NonStandardFormat + 1),
            SPSF_8kHz8BitMono = (SPSF_ExtendedAudioFormat + 1),
            SPSF_8kHz8BitStereo = (SPSF_8kHz8BitMono + 1),
            SPSF_8kHz16BitMono = (SPSF_8kHz8BitStereo + 1),
            SPSF_8kHz16BitStereo = (SPSF_8kHz16BitMono + 1),
            SPSF_11kHz8BitMono = (SPSF_8kHz16BitStereo + 1),
            SPSF_11kHz8BitStereo = (SPSF_11kHz8BitMono + 1),
            SPSF_11kHz16BitMono = (SPSF_11kHz8BitStereo + 1),
            SPSF_11kHz16BitStereo = (SPSF_11kHz16BitMono + 1),
            SPSF_12kHz8BitMono = (SPSF_11kHz16BitStereo + 1),
            SPSF_12kHz8BitStereo = (SPSF_12kHz8BitMono + 1),
            SPSF_12kHz16BitMono = (SPSF_12kHz8BitStereo + 1),
            SPSF_12kHz16BitStereo = (SPSF_12kHz16BitMono + 1),
            SPSF_16kHz8BitMono = (SPSF_12kHz16BitStereo + 1),
            SPSF_16kHz8BitStereo = (SPSF_16kHz8BitMono + 1),
            SPSF_16kHz16BitMono = (SPSF_16kHz8BitStereo + 1),
            SPSF_16kHz16BitStereo = (SPSF_16kHz16BitMono + 1),
            SPSF_22kHz8BitMono = (SPSF_16kHz16BitStereo + 1),
            SPSF_22kHz8BitStereo = (SPSF_22kHz8BitMono + 1),
            SPSF_22kHz16BitMono = (SPSF_22kHz8BitStereo + 1),
            SPSF_22kHz16BitStereo = (SPSF_22kHz16BitMono + 1),
            SPSF_24kHz8BitMono = (SPSF_22kHz16BitStereo + 1),
            SPSF_24kHz8BitStereo = (SPSF_24kHz8BitMono + 1),
            SPSF_24kHz16BitMono = (SPSF_24kHz8BitStereo + 1),
            SPSF_24kHz16BitStereo = (SPSF_24kHz16BitMono + 1),
            SPSF_32kHz8BitMono = (SPSF_24kHz16BitStereo + 1),
            SPSF_32kHz8BitStereo = (SPSF_32kHz8BitMono + 1),
            SPSF_32kHz16BitMono = (SPSF_32kHz8BitStereo + 1),
            SPSF_32kHz16BitStereo = (SPSF_32kHz16BitMono + 1),
            SPSF_44kHz8BitMono = (SPSF_32kHz16BitStereo + 1),
            SPSF_44kHz8BitStereo = (SPSF_44kHz8BitMono + 1),
            SPSF_44kHz16BitMono = (SPSF_44kHz8BitStereo + 1),
            SPSF_44kHz16BitStereo = (SPSF_44kHz16BitMono + 1),
            SPSF_48kHz8BitMono = (SPSF_44kHz16BitStereo + 1),
            SPSF_48kHz8BitStereo = (SPSF_48kHz8BitMono + 1),
            SPSF_48kHz16BitMono = (SPSF_48kHz8BitStereo + 1),
            SPSF_48kHz16BitStereo = (SPSF_48kHz16BitMono + 1),
            SPSF_TrueSpeech_8kHz1BitMono = (SPSF_48kHz16BitStereo + 1),
            SPSF_CCITT_ALaw_8kHzMono = (SPSF_TrueSpeech_8kHz1BitMono + 1),
            SPSF_CCITT_ALaw_8kHzStereo = (SPSF_CCITT_ALaw_8kHzMono + 1),
            SPSF_CCITT_ALaw_11kHzMono = (SPSF_CCITT_ALaw_8kHzStereo + 1),
            SPSF_CCITT_ALaw_11kHzStereo = (SPSF_CCITT_ALaw_11kHzMono + 1),
            SPSF_CCITT_ALaw_22kHzMono = (SPSF_CCITT_ALaw_11kHzStereo + 1),
            SPSF_CCITT_ALaw_22kHzStereo = (SPSF_CCITT_ALaw_22kHzMono + 1),
            SPSF_CCITT_ALaw_44kHzMono = (SPSF_CCITT_ALaw_22kHzStereo + 1),
            SPSF_CCITT_ALaw_44kHzStereo = (SPSF_CCITT_ALaw_44kHzMono + 1),
            SPSF_CCITT_uLaw_8kHzMono = (SPSF_CCITT_ALaw_44kHzStereo + 1),
            SPSF_CCITT_uLaw_8kHzStereo = (SPSF_CCITT_uLaw_8kHzMono + 1),
            SPSF_CCITT_uLaw_11kHzMono = (SPSF_CCITT_uLaw_8kHzStereo + 1),
            SPSF_CCITT_uLaw_11kHzStereo = (SPSF_CCITT_uLaw_11kHzMono + 1),
            SPSF_CCITT_uLaw_22kHzMono = (SPSF_CCITT_uLaw_11kHzStereo + 1),
            SPSF_CCITT_uLaw_22kHzStereo = (SPSF_CCITT_uLaw_22kHzMono + 1),
            SPSF_CCITT_uLaw_44kHzMono = (SPSF_CCITT_uLaw_22kHzStereo + 1),
            SPSF_CCITT_uLaw_44kHzStereo = (SPSF_CCITT_uLaw_44kHzMono + 1),
            SPSF_ADPCM_8kHzMono = (SPSF_CCITT_uLaw_44kHzStereo + 1),
            SPSF_ADPCM_8kHzStereo = (SPSF_ADPCM_8kHzMono + 1),
            SPSF_ADPCM_11kHzMono = (SPSF_ADPCM_8kHzStereo + 1),
            SPSF_ADPCM_11kHzStereo = (SPSF_ADPCM_11kHzMono + 1),
            SPSF_ADPCM_22kHzMono = (SPSF_ADPCM_11kHzStereo + 1),
            SPSF_ADPCM_22kHzStereo = (SPSF_ADPCM_22kHzMono + 1),
            SPSF_ADPCM_44kHzMono = (SPSF_ADPCM_22kHzStereo + 1),
            SPSF_ADPCM_44kHzStereo = (SPSF_ADPCM_44kHzMono + 1),
            SPSF_GSM610_8kHzMono = (SPSF_ADPCM_44kHzStereo + 1),
            SPSF_GSM610_11kHzMono = (SPSF_GSM610_8kHzMono + 1),
            SPSF_GSM610_22kHzMono = (SPSF_GSM610_11kHzMono + 1),
            SPSF_GSM610_44kHzMono = (SPSF_GSM610_22kHzMono + 1),
            SPSF_NUM_FORMATS = (SPSF_GSM610_44kHzMono + 1);
    }

    public static class WaveFormatEx extends Structure
    {
        static final List<String> FIELDS = Collections.unmodifiableList(Arrays.asList("wFormatTag", "nChannels", "nSamplesPerSec", "nAvgBytesPerSec", "nBlockAlign", "wBitsPerSample", "cbSize", "_extra"));
        public static class ByReference extends WaveFormatEx implements Structure.ByReference
        {
            public ByReference()
            {
            }
            public ByReference(Pointer memory)
            {
                super(memory);
            }
        }
        public WaveFormatEx()
        {
            super();
        }
        public WaveFormatEx(Pointer memory)
        {
            super(memory);
            this.read();
        }
        public short wFormatTag;
        public short nChannels;
        public int nSamplesPerSec;
        public int nAvgBytesPerSec;
        public short nBlockAlign;
        public short wBitsPerSample;
        public short cbSize;
        public final byte[] _extra = new byte[32];
        @Override
        protected List<String> getFieldOrder()
        {
            return FIELDS;
        }
    }

    public static WaveFormatEx SpConvertStreamFormatEnum(int format)
    {
        WaveFormatEx pwfex = new WaveFormatEx();
        if (format >= ESpFormat.SPSF_8kHz8BitMono && format <= ESpFormat.SPSF_48kHz16BitStereo)
        {
            int index = format - ESpFormat.SPSF_8kHz8BitMono;
            boolean isStereo = (index & 0x1) != 0,
                    is16 = (index & 0x2) != 0;
            int khz = (index & 0x3c) >> 2,
                akhz[] = { 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000 };
            pwfex.wFormatTag = 0x0001;
            pwfex.nChannels = pwfex.nBlockAlign = isStereo ? (short)2 : (short)1;
            pwfex.nSamplesPerSec = akhz[khz < akhz.length ? khz : 0];
            pwfex.wBitsPerSample = 8;
            if (is16)
            {
                pwfex.wBitsPerSample *= 2;
                pwfex.nBlockAlign *= 2;
            }
            pwfex.nAvgBytesPerSec = pwfex.nSamplesPerSec * pwfex.nBlockAlign;
            pwfex.cbSize = 0;
        }
        else if (format == ESpFormat.SPSF_TrueSpeech_8kHz1BitMono)
        {
            pwfex.wFormatTag = 0x0022;
            pwfex.nChannels = 1;
            pwfex.nSamplesPerSec = 8000;
            pwfex.nAvgBytesPerSec = 1067;
            pwfex.nBlockAlign = 32;
            pwfex.wBitsPerSample = 1;
            pwfex.cbSize = 32;
            pwfex._extra[0] = 1;
            pwfex._extra[2] = (byte)0xF0;
        }
        else if (format >= ESpFormat.SPSF_CCITT_ALaw_8kHzMono && format <= ESpFormat.SPSF_CCITT_ALaw_44kHzStereo)
        {
            int index = format - ESpFormat.SPSF_CCITT_ALaw_8kHzMono,
                khz = index / 2,
                akhz[] = { 8000, 11025, 22050, 44100 };
            boolean isStereo = (index & 0x1) != 0;
            pwfex.wFormatTag = 0x0006;
            pwfex.nChannels = pwfex.nBlockAlign = isStereo ? (short)2 : (short)1;
            pwfex.nSamplesPerSec = akhz[khz < akhz.length ? khz : 0];
            pwfex.wBitsPerSample = 8;
            pwfex.nAvgBytesPerSec = pwfex.nSamplesPerSec * pwfex.nBlockAlign;
            pwfex.cbSize = 0;
        }
        else if (format >= ESpFormat.SPSF_CCITT_uLaw_8kHzMono && format <= ESpFormat.SPSF_CCITT_uLaw_44kHzStereo)
        {
            int index = format - ESpFormat.SPSF_CCITT_uLaw_8kHzMono,
                khz = index / 2,
                akhz[] = { 8000, 11025, 22050, 44100 };
            boolean isStereo = (index & 0x1) != 0;
            pwfex.wFormatTag = 0x0007;
            pwfex.nChannels = pwfex.nBlockAlign = isStereo ? (short)2 : (short)1;
            pwfex.nSamplesPerSec = akhz[khz < akhz.length ? khz : 0];
            pwfex.wBitsPerSample = 8;
            pwfex.nAvgBytesPerSec = pwfex.nSamplesPerSec * pwfex.nBlockAlign;
            pwfex.cbSize = 0;
        }
        else if (format >= ESpFormat.SPSF_ADPCM_8kHzMono && format <= ESpFormat.SPSF_ADPCM_44kHzStereo)
        {
            int akhz[] = { 8000, 11025, 22050, 44100 },
                bytesPerSec[] = { 4096, 8192, 5644, 11289, 11155, 22311, 22179, 44359 };
            short blockAlign[] = { 256, 256, 512, 1024 };
            byte extra811[] = { (byte)0xF4, 0x01, 0x07, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, (byte)0xFF,
                    0x00, 0x00, 0x00, 0x00, (byte)0xC0, 0x00, 0x40, 0x00, (byte)0xF0, 0x00, 0x00, 0x00, (byte)0xCC,
                    0x01, 0x30, (byte)0xFF, (byte)0x88, 0x01, 0x18, (byte)0xFF },
                 extra22[] = { (byte)0xF4, 0x03, 0x07, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, (byte)0xFF,
                            0x00, 0x00, 0x00, 0x00, (byte)0xC0, 0x00, 0x40, 0x00, (byte)0xF0, 0x00, 0x00, 0x00,
                            (byte)0xCC, 0x01, 0x30, (byte)0xFF, (byte)0x88, 0x01, 0x18, (byte)0xFF },
                 extra44[] = { (byte)0xF4, 0x07, 0x07, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, (byte)0xFF,
                            0x00, 0x00, 0x00, 0x00, (byte)0xC0, 0x00, 0x40, 0x00, (byte)0xF0, 0x00, 0x00, 0x00,
                            (byte)0xCC, 0x01, 0x30, (byte)0xFF, (byte)0x88, 0x01, 0x18, (byte)0xFF },
                 extra[][] = { extra811, extra811, extra22, extra44 };
            int idx = format - ESpFormat.SPSF_ADPCM_8kHzMono, khz = idx / 2;
            boolean isStereo = (idx & 0x1) != 0;
            pwfex.wFormatTag = 0x0002;
            pwfex.nChannels = isStereo ? (short)2 : (short)1;
            pwfex.nSamplesPerSec = akhz[khz < akhz.length ? khz : 0];
            pwfex.nAvgBytesPerSec = bytesPerSec[khz < bytesPerSec.length ? khz : 0];
            pwfex.nBlockAlign = (short)(blockAlign[khz < blockAlign.length ? khz : 0] * pwfex.nChannels);
            pwfex.wBitsPerSample = 4;
            pwfex.cbSize = 32;
            System.arraycopy(extra[khz < extra.length ? khz : 0], 0, pwfex._extra, 0, 32);
        }
        else if (format >= ESpFormat.SPSF_GSM610_8kHzMono && format <= ESpFormat.SPSF_GSM610_44kHzMono)
        {
            int akhz[] = { 8000, 11025, 22050, 44100 },
                bytesPerSec[] = { 1625, 2239, 4478, 8957 },
                index = format - ESpFormat.SPSF_GSM610_8kHzMono;
            pwfex.wFormatTag = 0x0031;
            pwfex.nChannels = 1;
            pwfex.nSamplesPerSec = akhz[index < akhz.length ? index : 0];
            pwfex.nAvgBytesPerSec = bytesPerSec[index < bytesPerSec.length ? index : 0];
            pwfex.nBlockAlign = 65;
            pwfex.wBitsPerSample = 0;
            pwfex.cbSize = 2;
            pwfex._extra[0] = 0x40;
            pwfex._extra[1] = 0x01;
        }
        return pwfex;
    }

}
