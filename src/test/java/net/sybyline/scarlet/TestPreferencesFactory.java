package net.sybyline.scarlet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Test JVM isolation: never read or write the maintainer's OS preference store. */
public final class TestPreferencesFactory implements PreferencesFactory
{
    private static final Preferences USER = new MemoryNode(null, "");
    private static final Preferences SYSTEM = new MemoryNode(null, "");
    @Override public Preferences userRoot() { return USER; }
    @Override public Preferences systemRoot() { return SYSTEM; }

    private static final class MemoryNode extends AbstractPreferences
    {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, MemoryNode> children = new ConcurrentHashMap<>();
        MemoryNode(AbstractPreferences parent, String name) { super(parent, name); }
        @Override protected void putSpi(String key, String value) { this.values.put(key, value); }
        @Override protected String getSpi(String key) { return this.values.get(key); }
        @Override protected void removeSpi(String key) { this.values.remove(key); }
        @Override protected void removeNodeSpi() {
            this.values.clear(); this.children.clear();
            if (parent() instanceof MemoryNode) ((MemoryNode) parent()).children.remove(name());
        }
        @Override protected String[] keysSpi() { return this.values.keySet().toArray(new String[0]); }
        @Override protected String[] childrenNamesSpi() { return this.children.keySet().toArray(new String[0]); }
        @Override protected AbstractPreferences childSpi(String name) {
            return this.children.computeIfAbsent(name, value -> new MemoryNode(this, value));
        }
        @Override protected void syncSpi() { }
        @Override protected void flushSpi() { }
    }
}
