package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Pure argument construction only; no registry reads, process scans or game launches. */
class VrcLaunchArgumentsTest
{
    private static final String LOCATION = "wrld_00000000-0000-0000-0000-000000000001:123"
        + "~group(grp_00000000-0000-0000-0000-000000000002)~groupAccessType(members)~ageGate~region(us)";

    @Test void desktopArgumentsPreserveSecureAgeGatedTargetWithoutProfileSelector()
    {
        List<String> arguments = VrcLaunch.clientArguments(LOCATION, "synthetic secure+name", VrcLaunch.LaunchMode.DESKTOP);
        assertTrue(arguments.contains("--no-vr"));
        assertEquals(1L, arguments.stream().filter(value -> value.startsWith("vrchat://")).count());
        assertTrue(arguments.contains(VrcLaunch.buildLaunchUri(LOCATION, "synthetic secure+name")));
        assertTrue(arguments.stream().noneMatch(value -> value.startsWith("--profile")));
        assertEquals(6, arguments.size());
    }

    @Test void vrModeDoesNotAddDesktopOrInventedAccountSelection()
    {
        List<String> arguments = VrcLaunch.clientArguments(LOCATION, null, VrcLaunch.LaunchMode.VR);
        assertFalse(arguments.contains("--no-vr"));
        assertTrue(arguments.stream().noneMatch(value -> value.startsWith("--profile")));
        assertTrue(arguments.contains(VrcLaunch.buildLaunchUri(LOCATION, null)));
        assertEquals(5, arguments.size());
    }

    @Test void defaultModeIsDesktopAndExistingLoggingFlagsRemain()
    {
        List<String> arguments = VrcLaunch.clientArguments(LOCATION, null, null);
        assertEquals(VrcLaunch.clientArguments(LOCATION, null, VrcLaunch.LaunchMode.DESKTOP), arguments);
        assertTrue(arguments.contains("--enable-sdk-log-levels"));
        assertTrue(arguments.contains("--enable-udon-debug-logging"));
        assertTrue(arguments.contains("--enable-verbose-logging"));
        assertTrue(arguments.stream().anyMatch(value -> value.startsWith("--log-debug-levels=")));
    }

    @Test void missingTargetDoesNotProduceNullOrInventedJoinArgument()
    {
        List<String> arguments = VrcLaunch.clientArguments(null, "synthetic-private-value", VrcLaunch.LaunchMode.DESKTOP);
        assertTrue(arguments.stream().noneMatch(value -> value == null || value.contains("synthetic-private-value") || value.startsWith("vrchat://")));
        assertEquals(5, arguments.size());
    }

    @Test void eachCommandReceivesIndependentArgumentsAndPrivateOutput()
    {
        List<String> first = VrcLaunch.clientArguments(LOCATION, null, VrcLaunch.LaunchMode.DESKTOP);
        first.clear();
        assertEquals(6, VrcLaunch.clientArguments(LOCATION, null, VrcLaunch.LaunchMode.DESKTOP).size());
        ProcessBuilder builder = VrcLaunch.privateOutput(new ProcessBuilder("synthetic-never-executed"));
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput());
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError());
    }
}
