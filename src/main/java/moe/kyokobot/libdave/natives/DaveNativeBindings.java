package moe.kyokobot.libdave.natives;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import moe.kyokobot.libdave.RosterMap;
import moe.kyokobot.libdave.callbacks.DaveLogSink;
import moe.kyokobot.libdave.callbacks.EncryptorProtocolVersionChangedCallback;
import moe.kyokobot.libdave.callbacks.MLSFailureCallback;
import net.sybyline.scarlet.util.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local override of impl-jni's DaveNativeBindings with explicit desktop-native loading.
 */
public class DaveNativeBindings {
    private static final Logger LOG = LoggerFactory.getLogger(DaveNativeBindings.class);
    private static volatile boolean loaded;

    private DaveNativeBindings() {
    }

    public static DaveNativeBindings inst() {
        ensureLoaded();
        return new DaveNativeBindings();
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        Path extracted = extractBundledNative();
        if (extracted != null) {
            System.load(extracted.toAbsolutePath().normalize().toString());
            loaded = true;
            return;
        }
        LOG.info("No bundled libdave-jvm native found for {}; falling back to System.loadLibrary(dave-jvm)",
            Platform.describe());
        System.loadLibrary("dave-jvm");
        loaded = true;
    }

    private static Path extractBundledNative() {
        String resourceName = getNativeLibraryName();
        for (String resourcePath : getNativeResourceCandidates()) {
            URL resource = DaveNativeBindings.class.getClassLoader().getResource(resourcePath + "/" + resourceName);
            if (resource == null) {
                continue;
            }
            try {
                Path tempDir = Files.createTempDirectory("scarlet-libdave-jvm");
                tempDir.toFile().deleteOnExit();
                Path target = tempDir.resolve(resourceName);
                try (InputStream input = resource.openStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                target.toFile().deleteOnExit();
                LOG.info("Loaded bundled libdave-jvm native from classpath resource {}/{}", resourcePath, resourceName);
                return target;
            } catch (IOException e) {
                LOG.warn("Failed to extract bundled libdave-jvm native from {}/{}", resourcePath, resourceName, e);
            }
        }
        return null;
    }

    private static List<String> getNativeResourceCandidates() {
        List<String> candidates = new ArrayList<>();
        switch (Platform.CURRENT) {
            case NT:
                if (Platform.isArm64()) {
                    candidates.add("natives/win-aarch64");
                }
                if (Platform.isX86_64()) {
                    candidates.add("natives/win-x86-64");
                } else if (Platform.is32Bit()) {
                    candidates.add("natives/win-x86");
                }
                break;
            case XNU:
                candidates.add("natives/darwin");
                if (Platform.isArm64()) {
                    candidates.add("natives/darwin-arm64");
                    candidates.add("natives/darwin-arm64e");
                } else if (Platform.isX86_64()) {
                    candidates.add("natives/darwin-x86-64");
                }
                break;
            case $NIX:
                String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
                String archDir = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64"
                    : arch.contains("x86_64") || arch.contains("amd64") || arch.contains("x64") ? "x86-64"
                    : arch.contains("x86") || arch.contains("i386") || arch.contains("i686") ? "x86"
                    : arch.startsWith("arm") ? "arm"
                    : arch;
                candidates.add("natives/linux-" + archDir);
                candidates.add("natives/linux-musl-" + archDir);
                break;
            default:
                break;
        }
        return candidates;
    }

    private static String getNativeLibraryName() {
        switch (Platform.CURRENT) {
            case NT:
                return "dave-jvm.dll";
            case XNU:
                return "libdave-jvm.dylib";
            default:
                return "libdave-jvm.so";
        }
    }

    public native void daveSetLogSink(DaveLogSink sink);

    public native int daveMaxSupportedProtocolVersion();

    public native long daveSessionCreate(String context, String authSessionId, MLSFailureCallback callback);

    public native void daveSessionDestroy(long sessionHandle);

    public native void daveSessionInit(long sessionHandle, int version, long groupId, String selfUserId);

    public native void daveSessionReset(long sessionHandle);

    public native void daveSessionSetProtocolVersion(long sessionHandle, int version);

    public native int daveSessionGetProtocolVersion(long sessionHandle);

    public native byte[] daveSessionGetLastEpochAuthenticator(long sessionHandle);

    public native void daveSessionSetExternalSender(long sessionHandle, byte[] externalSender);

    public native byte[] daveSessionProcessProposals(long sessionHandle, byte[] proposals, String[] recognizedUserIds);

    public native Object daveSessionProcessCommit(long sessionHandle, byte[] commit);

    public native RosterMap daveSessionProcessWelcome(long sessionHandle, byte[] welcome, String[] recognizedUserIds);

    public native byte[] daveSessionGetMarshalledKeyPackage(long sessionHandle);

    public native long daveSessionGetKeyRatchet(long sessionHandle, String userId);

    public native void daveSessionGetPairwiseFingerprint(long sessionHandle, int version, String userId, Consumer<byte[]> callback);

    public native byte[] daveKeyRatchetGetEncryptionKey(long keyRatchetHandle, int keyGeneration);

    public native void daveKeyRatchetDeleteKey(long keyRatchetHandle, int keyGeneration);

    public native void daveKeyRatchetDestroy(long keyRatchetHandle);

    public native long daveEncryptorCreate();

    public native void daveEncryptorDestroy(long encryptorHandle);

    public native void daveEncryptorSetKeyRatchet(long encryptorHandle, long keyRatchetHandle);

    public native void daveEncryptorSetPassthroughMode(long encryptorHandle, boolean passthroughMode);

    public native void daveEncryptorAssignSsrcToCodec(long encryptorHandle, int ssrc, int codecType);

    public native int daveEncryptorGetProtocolVersion(long encryptorHandle);

    public native long daveEncryptorGetMaxCiphertextByteSize(long encryptorHandle, int mediaType, long frameSize);

    public native int daveEncryptorEncrypt(long encryptorHandle, int mediaType, int ssrc, byte[] frame, byte[] encryptedFrame);

    public native int daveEncryptorEncrypt(long encryptorHandle, int mediaType, int ssrc, ByteBuffer frame, ByteBuffer encryptedFrame);

    public native int daveEncryptorEncrypt(long encryptorHandle, int mediaType, int ssrc, long framePtr, int frameSize, long encryptedFramePtr, int encryptedFrameCapacity);

    public native void daveEncryptorSetProtocolVersionChangedCallback(long encryptorHandle, EncryptorProtocolVersionChangedCallback callback);

    public native long daveDecryptorCreate();

    public native void daveDecryptorDestroy(long decryptorHandle);

    public native void daveDecryptorTransitionToKeyRatchet(long decryptorHandle, long keyRatchetHandle);

    public native void daveDecryptorTransitionToPassthroughMode(long decryptorHandle, boolean passthroughMode);

    public native int daveDecryptorDecrypt(long decryptorHandle, int mediaType, byte[] encryptedFrame, byte[] frame);

    public native int daveDecryptorDecrypt(long decryptorHandle, int mediaType, ByteBuffer encryptedFrame, ByteBuffer frame);

    public native int daveDecryptorDecrypt(long decryptorHandle, int mediaType, long encryptedFramePtr, int encryptedFrameSize, long framePtr, int frameCapacity);

    public native long daveDecryptorGetMaxPlaintextByteSize(long decryptorHandle, int mediaType, long encryptedFrameSize);
}
