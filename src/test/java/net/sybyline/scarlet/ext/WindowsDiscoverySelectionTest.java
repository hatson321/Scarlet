package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;

class WindowsDiscoverySelectionTest
{
    static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    static ProcessInfo name(long pid, String exe, long parent) { return new ProcessInfo(pid, null, null, exe, List.of(), parent); }
    @Test void identitiesAreLimitedToClientsAndCompleteLaunchDescendantChain()
    {
        List<ProcessInfo> names = new ArrayList<>(List.of(name(10, "launcher.exe", 0), name(11, "bootstrap.exe", 10),
            name(12, "nested.exe", 11), name(20, "VRChat.exe", 12), name(30, "VRChat.exe", 0)));
        for (int i = 100; i < 1100; i++) names.add(name(i, "unrelated.exe", 0));
        List<Long> queried = new ArrayList<>();
        List<ProcessInfo> result = ManagedVrcClient.enrichWindowsSnapshot(names, Set.of(10L), NOW, process -> {
            queried.add(process.pid()); return new ProcessInfo(process.pid(), NOW.minusSeconds(1), "test", process.executable(), List.of(), process.parentPid());
        });
        assertEquals(List.of(10L, 11L, 12L, 20L, 30L), queried);
        assertEquals(names.size(), result.size()); assertNull(result.get(5).startedAt());
        assertEquals(NOW.minusSeconds(1), result.get(2).startedAt());
    }
    @Test void closeInventoryReadsBothClientsButNoUnrelatedIdentity()
    {
        List<Long> queried = new ArrayList<>();
        List<ProcessInfo> result = ManagedVrcClient.enrichWindowsSnapshot(List.of(name(1,"shell.exe",0),name(2,"VRChat.exe",1),name(3,"VRChat.exe",0)),
            Set.of(), NOW, process -> { queried.add(process.pid()); return null; });
        assertEquals(List.of(2L,3L), queried); assertEquals(3,result.size());
        assertTrue(ManagedVrcClient.isClient(result.get(1)), "Unreadable identity must still block a duplicate launch");
    }
    @Test void reusedPidOrIncompleteEnumerationCannotBecomeOwnershipEvidence()
    {
        ProcessInfo client = name(2,"VRChat.exe",1);
        assertThrows(IllegalStateException.class, () -> ManagedVrcClient.enrichWindowsSnapshot(List.of(client),Set.of(),NOW,
            ignored -> new ProcessInfo(2,NOW.plusSeconds(1),"test","VRChat.exe",List.of(),1)));
        assertThrows(IllegalStateException.class, () -> ManagedVrcClient.enrichWindowsSnapshot(List.of(client,client),Set.of(),NOW, ignored -> null));
        assertThrows(IllegalStateException.class, () -> ManagedVrcClient.enrichWindowsSnapshot(List.of(name(1,null,0)),Set.of(),NOW, ignored -> null));
    }
}
