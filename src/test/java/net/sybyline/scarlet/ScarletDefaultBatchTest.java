package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletDefaultBatchTest {
    @TempDir Path temp;
    @Test void defaultsShareOneCommitButExplicitChangesStaySynchronous() throws Exception {
        int[] writes={0}; Path file=temp.resolve("settings.json");
        ScarletSettings settings=new ScarletSettings(null,file.toFile(),false) {
            @Override public synchronized void saveJson() { writes[0]++; super.saveJson(); }
        };
        settings.beginDefaultBatch();
        for(int i=0;i<50;i++)assertTrue(settings.new FileValuedBoolean("synthetic"+i,"value",true).get());
        assertEquals(0,writes[0]);
        settings.finishDefaultBatch(); assertEquals(1,writes[0]);
        assertTrue(Files.readString(file).contains("synthetic49"));
        settings.beginDefaultBatch();
        settings.new FileValuedBoolean("additional","value",false).get();
        settings.setObject("explicit-security-setting",Boolean.class,false);
        assertEquals(2,writes[0]); assertTrue(Files.readString(file).contains("explicit-security-setting"));
        settings.finishDefaultBatch(); assertEquals(2,writes[0]);
    }
}
