package net.sybyline.scarlet.server.discord;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.sun.jna.ptr.LongByReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dv8tion.jda.api.audio.dave.DaveProtocolCallbacks;
import net.dv8tion.jda.api.audio.dave.DaveSession;
import net.sybyline.scarlet.server.discord.dave.Dave;
import net.sybyline.scarlet.server.discord.dave.DaveCommitResult;
import net.sybyline.scarlet.server.discord.dave.DaveDecryptor;
import net.sybyline.scarlet.server.discord.dave.DaveEncryptor;
import net.sybyline.scarlet.server.discord.dave.DaveLibrary;
import net.sybyline.scarlet.server.discord.dave.DaveSessionInstance;
import net.sybyline.scarlet.server.discord.dave.DaveWelcomeResult;

public class DAudioDaveSession implements DaveSession, Closeable
{

    // This session only runs as the JNA fallback (a platform without a native libdave-jvm, e.g.
    // glibc arm64). It is otherwise unexercised, so it is heavily logged: when Discord voice thrashes
    // (join/leave loop) on such a platform, these lines pinpoint which DAVE handshake step failed.
    private static final Logger LOG = LoggerFactory.getLogger(DAudioDaveSession.class);

    public DAudioDaveSession(DaveProtocolCallbacks callbacks, long userId, long channelId)
    {
        this(callbacks, userId, channelId, Dave.INSTANCE.createSession(null, null, null, null), new DaveEncryptor());
    }

    DAudioDaveSession(DaveProtocolCallbacks callbacks, long userId, long channelId,
        DaveSessionInstance session, DaveEncryptor encryptor)
    {
        this.callbacks = callbacks;
        this.selfUserId = userId;
        this.channelId = channelId;
        this.session = session;
        this.encryptor = encryptor;
        this.decryptors = new ConcurrentHashMap<>();
        this.preparedTransitions = new ConcurrentHashMap<>();
        this.currentProtocolVersion = DaveLibrary.DAVE_DISABLED_PROTOCOL_VERSION;
        this.closed = false;
    }

    final DaveProtocolCallbacks callbacks;
    final long selfUserId, channelId;
    final DaveSessionInstance session;
    final DaveEncryptor encryptor;
    final Map<Long, DaveDecryptor> decryptors;
    final Map<Integer, Short> preparedTransitions;
    volatile short currentProtocolVersion;
    volatile boolean closed;

    @Override
    public synchronized void close()
    {
        if (this.closed)
            return;
        this.closed = true;
        try { this.encryptor.close(); }
        finally
        {
            try
            {
                for (DaveDecryptor decryptor : this.decryptors.values())
                    try { decryptor.close(); }
                    catch (RuntimeException ex) { LOG.warn("DAVE decryptor cleanup failed", ex); }
            }
            finally
            {
                this.decryptors.clear();
                this.preparedTransitions.clear();
                this.session.close();
            }
        }
    }

    @Override
    public int getMaxProtocolVersion()
    {
        return Short.toUnsignedInt(Dave.INSTANCE.maxSupportedProtocolVersion());
    }

    @Override
    public synchronized int getMaxEncryptedFrameSize(MediaType type, int frameSize)
    {
        if (this.closed)
            return frameSize;
        int mediaType;
        switch (type)
        {
        case AUDIO: mediaType = DaveLibrary.DAVE_MEDIA_TYPE_AUDIO; break;
        default: return frameSize;
        }
        return Math.toIntExact(this.encryptor.getMaxCiphertextByteSize(mediaType, Integer.toUnsignedLong(frameSize)));
    }

    @Override
    public synchronized int getMaxDecryptedFrameSize(MediaType media, long userId, int frameSize)
    {
        if (this.closed)
            return frameSize;
        int mediaType;
        switch (media)
        {
        case AUDIO: mediaType = DaveLibrary.DAVE_MEDIA_TYPE_AUDIO; break;
        default: return frameSize;
        }
        DaveDecryptor decryptor = this.decryptors.get(userId);
        if (decryptor == null)
            return frameSize;
        return Math.toIntExact(decryptor.getMaxPlaintextByteSize(mediaType, Integer.toUnsignedLong(frameSize)));
    }

    @Override
    public synchronized void assignSsrcToCodec(Codec codec, int ssrc)
    {
        if (this.closed)
            return;
        int codecType;
        switch (codec)
        {
        case OPUS: codecType = DaveLibrary.DAVE_CODEC_OPUS; break;
        default: return;
        }
        this.encryptor.assignSsrcToCodec(ssrc, codecType);
    }

    @Override
    public synchronized boolean encrypt(MediaType media, int ssrc, ByteBuffer data, ByteBuffer encrypted)
    {
        if (this.closed)
            return false;
        int mediaType;
        switch (media)
        {
        case AUDIO: mediaType = DaveLibrary.DAVE_MEDIA_TYPE_AUDIO; break;
        default: return false;
        }
        LongByReference bytesWritten = new LongByReference();
        int start = encrypted.position(), available = encrypted.remaining();
        int result = this.encryptor.encrypt(mediaType, ssrc, data, encrypted, bytesWritten);
        return finishOutput(encrypted, start, available, bytesWritten.getValue(),
            result == DaveLibrary.DAVE_ENCRYPTOR_RESULT_CODE_SUCCESS);
    }

    @Override
    public synchronized boolean decrypt(MediaType media, long userId, ByteBuffer encrypted, ByteBuffer decrypted)
    {
        if (this.closed)
            return false;
        int mediaType;
        switch (media)
        {
        case AUDIO: mediaType = DaveLibrary.DAVE_MEDIA_TYPE_AUDIO; break;
        default: return false;
        }
        DaveDecryptor decryptor = this.decryptors.get(userId);
        if (decryptor == null)
            return false;
        LongByReference bytesWritten = new LongByReference();
        int start = decrypted.position(), available = decrypted.remaining();
        int result = decryptor.decrypt(mediaType, encrypted, decrypted, bytesWritten);
        return finishOutput(decrypted, start, available, bytesWritten.getValue(),
            result == DaveLibrary.DAVE_DECRYPTOR_RESULT_CODE_SUCCESS);
    }

    private static boolean finishOutput(ByteBuffer output, int start, int available, long written, boolean success)
    {
        boolean valid = success && written >= 0L && written <= available;
        output.position(start);
        output.limit(start + (valid ? (int)written : 0));
        return valid;
    }

    @Override
    public synchronized void addUser(long userId)
    {
        if (this.closed)
            return;
        this.decryptors.computeIfAbsent(userId, $ -> Dave.INSTANCE.createDecryptor())
            ._prepareTransition(this.session, this.currentProtocolVersion, userId);
    }

    @Override
    public synchronized void removeUser(long userId)
    {
        if (this.closed)
            return;
        DaveDecryptor decryptor = this.decryptors.remove(userId);
        if (decryptor != null)
            decryptor.close();
    }

    @Override
    public synchronized void initialize()
    {
    }

    @Override
    public synchronized void destroy()
    {
        this.close();
    }

    @Override
    public synchronized void onSelectProtocolAck(int protocolVersion)
    {
        if (this.closed)
            return;
        LOG.debug("DAVE onSelectProtocolAck(protocolVersion={}) ch={} self={}", protocolVersion, this.channelId, this.selfUserId);
        this.handleDaveProtocolInit((short)protocolVersion);
    }

    @Override
    public synchronized void onDaveProtocolPrepareTransition(int transitionId, int protocolVersion)
    {
        if (this.closed)
            return;
        LOG.debug("DAVE onDaveProtocolPrepareTransition(transitionId={}, protocolVersion={})", transitionId, protocolVersion);
        this.prepareProtocolTransition(transitionId, (short)protocolVersion);
    }

    @Override
    public synchronized void onDaveProtocolExecuteTransition(int transitionId)
    {
        if (this.closed)
            return;
        LOG.debug("DAVE onDaveProtocolExecuteTransition(transitionId={})", transitionId);
        this.executeProtocolTransition(transitionId);
    }

    @Override
    public synchronized void onDaveProtocolPrepareEpoch(long epoch, int protocolVersion)
    {
        if (this.closed)
            return;
        LOG.debug("DAVE onDaveProtocolPrepareEpoch(epoch={}, protocolVersion={})", epoch, protocolVersion);
        this.handlePrepareEpoch(epoch, (short)protocolVersion);
    }

    @Override
    public synchronized void onDaveProtocolMLSExternalSenderPackage(ByteBuffer externalSenderPackage)
    {
        if (this.closed)
            return;
        this.session.setExternalSender(externalSenderPackage);
    }

    @Override
    public synchronized void onMLSProposals(ByteBuffer proposals)
    {
        if (this.closed)
            return;
        LOG.debug("DAVE onMLSProposals({} bytes)", proposals == null ? 0 : proposals.remaining());
        this.session.processProposals(proposals, this.getRecognizedUserIds(), this.callbacks::sendMLSCommitWelcome);
    }

    @Override
    public synchronized void onMLSPrepareCommitTransition(int transitionId, ByteBuffer commit)
    {
        if (this.closed)
            return;
        LOG.debug("DAVE onMLSPrepareCommitTransition(transitionId={}, {} bytes)", transitionId, commit == null ? 0 : commit.remaining());
        try (DaveCommitResult result = this.session.processCommit(commit))
        {
            if (result.isIgnored())
            {
                this.preparedTransitions.remove(transitionId);
            }
            else if (result.isFailed())
            {
                LOG.warn("DAVE commit failed for transitionId={}; re-sending key package and re-initialising", transitionId);
                this.sendInvalidCommitWelcome(transitionId);
                // Was `(short)transitionId` (flagged "smells sus" by the author): a transition id is not
                // a protocol version. Re-initialise at the session's actual protocol version instead.
                this.handleDaveProtocolInit(this.session.getProtocolVersion());
            }
            else
            {
                this.prepareProtocolTransition(transitionId, this.session.getProtocolVersion());
            }
        }
    }

    @Override
    public synchronized void onMLSWelcome(int transitionId, ByteBuffer welcome)
    {
        if (this.closed)
            return;
        LOG.debug("DAVE onMLSWelcome(transitionId={}, {} bytes)", transitionId, welcome == null ? 0 : welcome.remaining());
        try (DaveWelcomeResult result = this.session.processWelcome(welcome, this.getRecognizedUserIds()))
        {
            if (result != null && result._isHandleValid())
            {
                this.prepareProtocolTransition(transitionId, this.session.getProtocolVersion());
            }
            else
            {
                LOG.warn("DAVE welcome invalid for transitionId={}; re-sending key package and re-initialising", transitionId);
                this.sendInvalidCommitWelcome(transitionId);
                // Was `(short)transitionId` (flagged "smells sus"): re-initialise at the session's actual
                // protocol version, not the transition id.
                this.handleDaveProtocolInit(this.session.getProtocolVersion());
            }
        }
    }

    private String[] getRecognizedUserIds()
    {
        return Stream.concat(Stream.of(this.selfUserId), this.decryptors.keySet().stream())
            .map(Long::toUnsignedString)
            .toArray(String[]::new);
    }


    private void handleDaveProtocolInit(short protocolVersion)
    {
        if (protocolVersion > DaveLibrary.DAVE_DISABLED_PROTOCOL_VERSION)
        {
            this.handlePrepareEpoch(DaveLibrary.DAVE_MLS_NEW_GROUP_EXPECTED_EPOCH, protocolVersion);
        }
        else
        {
            this.prepareProtocolTransition(DaveLibrary.DAVE_INIT_TRANSITION_ID, protocolVersion);
            this.executeProtocolTransition(DaveLibrary.DAVE_INIT_TRANSITION_ID);
        }
    }

    private void handlePrepareEpoch(long epoch, short protocolVersion)
    {
        if (epoch != DaveLibrary.DAVE_MLS_NEW_GROUP_EXPECTED_EPOCH)
        {
            LOG.debug("DAVE handlePrepareEpoch: ignoring unexpected epoch {} (expected {})", epoch, DaveLibrary.DAVE_MLS_NEW_GROUP_EXPECTED_EPOCH);
            return;
        }
        try
        {
            this.session.init(protocolVersion, this.channelId, Long.toUnsignedString(this.selfUserId));
            this.session.getMarshalledKeyPackage(this.callbacks::sendMLSKeyPackage);
            LOG.debug("DAVE handlePrepareEpoch: MLS group initialised at protocolVersion={} and key package sent", protocolVersion);
        }
        catch (Throwable t)
        {
            // On a platform where the JNA fallback misbehaves this is where the E2EE handshake dies,
            // which Discord answers by dropping voice — hence the join/leave thrash. Surface it.
            LOG.error("DAVE handlePrepareEpoch failed at protocolVersion={} (voice will drop/thrash): {}", protocolVersion, t.toString(), t);
            throw t;
        }
    }

    private void prepareProtocolTransition(int transitionId, short protocolVersion)
    {
        this.decryptors.forEach((userId, decryptor) ->
        {
            if (this.selfUserId != userId.longValue())
            {
                decryptor._prepareTransition(this.session, protocolVersion, userId);
            }
        });
        if (transitionId == DaveLibrary.DAVE_INIT_TRANSITION_ID)
        {
            this.encryptor._processTransition(this.session, protocolVersion, this.selfUserId);
        }
        else
        {
            this.preparedTransitions.put(transitionId, protocolVersion);
            this.currentProtocolVersion = protocolVersion;
            this.callbacks.sendDaveProtocolReadyForTransition(transitionId);
        }
    }

    private void executeProtocolTransition(int transitionId)
    {
        Short protocolVersion = this.preparedTransitions.remove(transitionId);
        if (protocolVersion == null)
            return;
        if (protocolVersion == DaveLibrary.DAVE_DISABLED_PROTOCOL_VERSION)
            this.session.reset();
        this.encryptor._processTransition(this.session, protocolVersion, this.selfUserId);
    }

    private void sendInvalidCommitWelcome(int transitionId)
    {
        this.callbacks.sendMLSInvalidCommitWelcome(transitionId);
        this.session.getMarshalledKeyPackage(this.callbacks::sendMLSKeyPackage);
    }

}
