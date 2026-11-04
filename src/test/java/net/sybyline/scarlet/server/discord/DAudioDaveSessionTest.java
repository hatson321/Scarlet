package net.sybyline.scarlet.server.discord;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import com.sun.jna.ptr.LongByReference;
import net.dv8tion.jda.api.audio.dave.DaveSession.MediaType;
import net.sybyline.scarlet.server.discord.dave.*;

class DAudioDaveSessionTest {
    static class Encryptor extends DaveEncryptor {
        long length = 3;
        int status;
        int closes;
        Encryptor() { super((DaveLibrary.DAVEEncryptorHandle)null); }
        @Override public int encrypt(int media, int ssrc, ByteBuffer in, ByteBuffer out, LongByReference written) {
            written.setValue(length);
            return status;
        }
        @Override public void close() { closes++; }
    }
    static class Decryptor extends DaveDecryptor {
        long length = 3;
        int status;
        int closes;
        Decryptor() { super((DaveLibrary.DAVEDecryptorHandle)null); }
        @Override public int decrypt(int media, ByteBuffer in, ByteBuffer out, LongByReference written) {
            written.setValue(length);
            return status;
        }
        @Override public void close() { closes++; }
    }
    static class Session extends DaveSessionInstance {
        int closes;
        Session() { super((DaveLibrary.DAVESessionHandle)null); }
        @Override public void close() { closes++; }
    }

    @Test void encryptAndDecryptExposeOnlyWrittenBytesAtOriginalPosition() {
        Encryptor e = new Encryptor();
        Decryptor d = new Decryptor();
        DAudioDaveSession s = new DAudioDaveSession(null, 1, 2, new Session(), e);
        s.decryptors.put(3L, d);
        for (long size : new long[] {0, 3, 20}) {
            e.length = d.length = size;
            ByteBuffer out = ByteBuffer.allocateDirect(32);
            out.position(5); out.limit(25);
            assertTrue(s.encrypt(MediaType.AUDIO, 1, ByteBuffer.allocateDirect(1), out));
            assertEquals(5, out.position());
            assertEquals(size, out.remaining());
            out.clear(); out.position(5); out.limit(25);
            assertTrue(s.decrypt(MediaType.AUDIO, 3, ByteBuffer.allocateDirect(1), out));
            assertEquals(5, out.position());
            assertEquals(size, out.remaining());
        }
    }

    @Test void invalidNativeLengthsAndFailureExposeNoOutput() {
        Encryptor e = new Encryptor();
        Decryptor d = new Decryptor();
        DAudioDaveSession s = new DAudioDaveSession(null, 1, 2, new Session(), e);
        s.decryptors.put(3L, d);
        for (long size : new long[] {-1, 33, Long.MAX_VALUE}) {
            e.length = d.length = size;
            ByteBuffer out = ByteBuffer.allocateDirect(32);
            assertFalse(s.encrypt(MediaType.AUDIO, 1, out.duplicate(), out));
            assertEquals(0, out.remaining());
            out.clear();
            assertFalse(s.decrypt(MediaType.AUDIO, 3, out.duplicate(), out));
            assertEquals(0, out.remaining());
        }
        e.status = d.status = 1;
        e.length = d.length = 3;
        ByteBuffer out = ByteBuffer.allocateDirect(32);
        assertFalse(s.encrypt(MediaType.AUDIO, 1, out.duplicate(), out));
        assertEquals(0, out.remaining());
        out.clear();
        assertFalse(s.decrypt(MediaType.AUDIO, 3, out.duplicate(), out));
        assertEquals(0, out.remaining());
    }

    @Test void repeatAndConcurrentCleanupDestroyEachResourceOnce() throws Exception {
        Encryptor e = new Encryptor();
        Decryptor d = new Decryptor();
        Session nativeSession = new Session();
        DAudioDaveSession s = new DAudioDaveSession(null, 1, 2, nativeSession, e);
        s.decryptors.put(3L, d);
        Thread first = new Thread(s::close), second = new Thread(s::destroy);
        first.start(); second.start(); first.join(); second.join(); s.close();
        assertEquals(1, e.closes); assertEquals(1, d.closes); assertEquals(1, nativeSession.closes);
        assertTrue(s.decryptors.isEmpty());
        s.addUser(4);
        assertTrue(s.decryptors.isEmpty());
        assertFalse(s.encrypt(MediaType.AUDIO, 1, ByteBuffer.allocateDirect(1), ByteBuffer.allocateDirect(1)));
    }
}
