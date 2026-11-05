package net.sybyline.scarlet.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Font;
import java.util.List;
import org.junit.jupiter.api.Test;

class FontFallbackCoverageTest {
    static Font font(String name,String coverage) { return new Font(name,Font.PLAIN,12) {
        @Override public boolean canDisplay(int cp) { return coverage.indexOf(cp)>=0; }
    }; }
    @Test void cachedCandidateMustCoverTheEntireNewText() {
        Font base=font("synthetic-coverage","A"), one=font("one","Aα"), two=font("two","Aαβ");
        assertSame(one,Swing.fontForText("Aα",base,()->List.of(one)));
        assertSame(two,Swing.fontForText("Aαβ",base,()->List.of(one,two)));
        assertSame(base,Swing.fontForText("AαЖ",base,()->List.of(one)));
        assertSame(two,Swing.fontForText("Aαβ",base,()->{throw new AssertionError("valid cache lost");}));
    }
    @Test void mixedScriptMissDoesNotNegativeCacheSimplerText() {
        Font base=font("synthetic-no-negative","A"), one=font("one","Aα");
        assertSame(base,Swing.fontForText("AαЖ",base,()->List.of(one)));
        assertSame(one,Swing.fontForText("Aα",base,()->List.of(one)));
    }
}
