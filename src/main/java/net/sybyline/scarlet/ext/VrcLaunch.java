package net.sybyline.scarlet.ext;

import java.io.File;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;

import net.sybyline.scarlet.util.Location;
import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.util.Sys;
import net.sybyline.scarlet.util.URLs;

public interface VrcLaunch
{

    Logger LOG = LoggerFactory.getLogger("Scarlet/VrcLaunch");

    ManagedVrcClient.Tracker MANAGED_CLIENTS = new ManagedVrcClient.Tracker();

    /** Only a uniquely identified client launched in this Scarlet session is returned. */
    static ManagedVrcClient.Client managedClient()
    {
        return MANAGED_CLIENTS.current();
    }

    /** Only a verified managed client can receive a normal close request. */
    static ManagedVrcClient.CloseResult closeManagedClient(ManagedVrcClient.Client expected,
                                                          java.util.function.BooleanSupplier revalidate)
    {
        return MANAGED_CLIENTS.close(expected, revalidate);
    }

    // vrchat://launch?ref=<Organization>&id=<Location>&shortName=<ShortName|SecureName>&attach=<integer>

    enum LaunchMode
    {
        VR(false),
        DESKTOP(true);

        LaunchMode(boolean noVr)
        {
            this.noVr = noVr;
        }
        final boolean noVr;
    }

    static void launch(String userId, Location location) throws Exception
    {
        launch(userId, location.world+':'+location.instance);
    }
    static void launch(String userId, Location location, boolean noVr) throws Exception
    {
        launch(userId, location.world+':'+location.instance, noVr);
    }
    static void launch(String userId, Location location, LaunchMode mode) throws Exception
    {
        launch(userId, location.world+':'+location.instance, mode);
    }
    static void launch(String userId, String location) throws Exception
    {
        launch(userId, location, LaunchMode.DESKTOP);
    }
    static void launch(String userId, String location, boolean noVr) throws Exception
    {
        launch(userId, location, noVr ? LaunchMode.DESKTOP : LaunchMode.VR);
    }
    static void launch(String userId, String location, LaunchMode mode) throws Exception
    {
        launch(userId, location, null, mode);
    }
    /**
     * Launches VRChat into a specific instance.
     *
     * <p>{@code shortName} is the instance's short/secure name from the VRChat API.
     * It is <b>required</b> to deep-link into any non-public instance (group,
     * group+, friends, invite, private): without it VRChat cannot resolve or
     * authorize the join and drops the client into the error/loading world even
     * though the instance exists. Public instances resolve from the location
     * alone, so a null shortName is fine for those.
     *
     * <p>The retained {@code userId} parameter is not a client profile selector.
     * VRChat uses its existing login; Scarlet does not switch client accounts.
     */
    static void launch(String userId, String location, String shortName, LaunchMode mode) throws Exception
    {
        launch(userId, location, shortName, mode, true);
    }

    /** Wizard opening is not permission to stop a client that appeared after its UI check. */
    static void launchIfNotRunning(String userId, String location, String shortName, LaunchMode mode) throws Exception
    {
        launch(userId, location, shortName, mode, false);
    }

    /** Startup's opt-in/account guard is checked again immediately before native launch dispatch. */
    static void launchIfNotRunning(String userId, String location, String shortName, LaunchMode mode,
        java.util.function.BooleanSupplier allowed) throws Exception
    {
        MANAGED_CLIENTS.launch(location, false, guard -> {
            ManagedVrcClient.LaunchGuard checked = startupGuard(guard, allowed);
            checked.check();
            return ManagedVrcClient.startedProcess(Platform.CURRENT.isNT()
                ? launch_win(userId, location, shortName, mode, checked)
                : launch_linux(userId, location, shortName, mode, checked));
        });
    }

    static ManagedVrcClient.LaunchGuard startupGuard(ManagedVrcClient.LaunchGuard guard,
        java.util.function.BooleanSupplier allowed)
    {
        return () -> {
            guard.check();
            if (!allowed.getAsBoolean()) throw new InterruptedException("Startup auto-join cancelled before launch");
        };
    }

    /** Auto-follow keeps its account/settings guard at close and launch boundaries. */
    static void launchAutomatic(String userId, String location, String shortName, java.util.function.BooleanSupplier allowed) throws Exception
    {
        MANAGED_CLIENTS.launch(location, true, guard -> {
            ManagedVrcClient.LaunchGuard checked = startupGuard(guard, allowed);
            checked.check();
            return ManagedVrcClient.startedProcess(Platform.CURRENT.isNT()
                ? launch_win(userId, location, shortName, LaunchMode.DESKTOP, checked)
                : launch_linux(userId, location, shortName, LaunchMode.DESKTOP, checked));
        }, null, allowed, allowed, null);
    }

    /** Exact-client recovery; optional guarded force-stop never bypasses quiet-exit verification. */
    static void recoverManagedClient(ManagedVrcClient.Client expected, String userId,
        java.util.function.BooleanSupplier allowed, java.util.function.BooleanSupplier closeAllowed,
        java.util.function.BooleanSupplier forceAllowed,
        java.util.concurrent.Callable<String> prepareSecureJoin) throws Exception
    {
        if (expected == null) throw new IllegalArgumentException("Managed startup client required");
        MANAGED_CLIENTS.launch(expected.requestedLocation(), true, guard -> {
            ManagedVrcClient.LaunchGuard checked = startupGuard(guard, allowed);
            checked.check();
            String shortName = prepareSecureJoin.call(); // Recheck target after verified shutdown, not just before close.
            if (shortName == null || shortName.isBlank()) throw new java.io.IOException("Secure startup join unavailable");
            checked.check();
            return ManagedVrcClient.startedProcess(Platform.CURRENT.isNT()
                ? launch_win(userId, expected.requestedLocation(), shortName, LaunchMode.DESKTOP, checked)
                : launch_linux(userId, expected.requestedLocation(), shortName, LaunchMode.DESKTOP, checked));
        }, expected, allowed, closeAllowed, forceAllowed);
    }

    static void launch(String userId, String location, String shortName, LaunchMode mode, boolean allowManagedRestart) throws Exception
    {
        MANAGED_CLIENTS.launch(location, allowManagedRestart, guard -> ManagedVrcClient.startedProcess(
            Platform.CURRENT.isNT() ? launch_win(userId, location, shortName, mode, guard)
                : launch_linux(userId, location, shortName, mode, guard)));
    }

    static void cancelPendingLaunches()
    {
        MANAGED_CLIENTS.cancelPendingLaunches();
    }

    static void stopLaunching()
    {
        MANAGED_CLIENTS.stopLaunching();
    }

    /** Appends the VRChat deep-link query for a location, including {@code &shortName=} when present. */
    static String buildLaunchUri(String location, String shortName)
    {
        return buildLaunchUri(location, shortName, false);
    }

    /**
     * Builds the VRChat deep-link URI.
     *
     * @param attach when true, appends {@code &attach=1}, asking VRChat to handle
     *        the join in the <b>already-running</b> client (switch instance in
     *        place) rather than spinning up a new one. Only meaningful when a client
     *        is already running and the URI is fired through the OS protocol handler.
     */
    static String buildLaunchUri(String location, String shortName, boolean attach)
    {
        if (location == null)
            return null;
        StringBuilder uri = new StringBuilder("vrchat://launch?ref=Hatson321ScarletLogger&id=").append(location);
        if (shortName != null && !shortName.trim().isEmpty())
            uri.append("&shortName=").append(URLs.encode(shortName.trim()));
        if (attach)
            uri.append("&attach=1");
        return uri.toString();
    }

    /** Unknown inventory and an unresolved startup are conservatively treated as running. */
    static boolean isVrChatRunning()
    {
        return MANAGED_CLIENTS.hasClientsOrPending();
    }

    /** Confirmed live clients only; uncertainty must not authorize a privacy rescan. */
    static java.util.List<String> confirmedClientPids()
    {
        return MANAGED_CLIENTS.confirmedClientPids();
    }

    /** Shared client arguments; an account ID is not a numeric VRChat profile selector. */
    static java.util.List<String> clientArguments(String location, String shortName, LaunchMode mode)
    {
        if (mode == null)
            mode = LaunchMode.DESKTOP;
        java.util.List<String> arguments = new java.util.ArrayList<>();
        if (mode.noVr) arguments.add("--no-vr");
        arguments.add("--enable-sdk-log-levels");
        arguments.add("--enable-udon-debug-logging");
        arguments.add("--enable-verbose-logging");
        arguments.add("--log-debug-levels=API;All;Always;AssetBundleDownloadManager;ContentCreator;Errors;NetworkData;NetworkProcessing;NetworkTransport;Warnings");
        String uri = buildLaunchUri(location, shortName);
        if (uri != null) arguments.add(uri);
        // Use VRChat's existing login/profile. Never guess a numeric profile from a user ID.
        return arguments;
    }

    /**
     * Launch VRChat on Linux via Steam.
     *
     * Strategy (each step falls back to the next on failure):
     *   1. steam -applaunch 438100 <args>   — most reliable, passes args directly
     *   2. steam steam://rungameid/438100    — works when steam binary is present but arg passing is unreliable
     *   3. xdg-open vrchat://...            — original behaviour, relies on URI handler registration
     */
    static Process launch_linux(String userId, String location, String shortName, LaunchMode mode, ManagedVrcClient.LaunchGuard guard) throws Exception
    {
        String vrchatUri = buildLaunchUri(location, shortName);
        java.util.List<String> vrcArgs = clientArguments(location, shortName, mode);

        // Strategy 1: steam -applaunch
        if (Sys.hasInPath("steam"))
        {
            try
            {
                java.util.List<String> cmd = new java.util.ArrayList<>();
                cmd.add("steam");
                cmd.add("-applaunch");
                cmd.add(VrcAppData.VRCHAT_APP_ID);
                cmd.addAll(vrcArgs);
                LOG.info("Requesting VRChat launch through Steam; launch arguments are private");
                ProcessBuilder builder = privateOutput(new ProcessBuilder(cmd));
                guard.check();
                return builder.start();
            }
            catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception ex)
            {
                LOG.warn("Steam launch process could not be started; trying its URI handler ({})", ex.getClass().getSimpleName());
            }

            // Strategy 2: steam steam://rungameid/438100//<args>
            // Steam accepts launch arguments appended after a "//" separator; without
            // them this would open VRChat with no instance and land on the home world.
            try
            {
                StringBuilder runGameId = new StringBuilder("steam://rungameid/").append(VrcAppData.VRCHAT_APP_ID);
                if (!vrcArgs.isEmpty())
                {
                    runGameId.append("//");
                    for (int i = 0; i < vrcArgs.size(); i++)
                    {
                        if (i > 0)
                            runGameId.append(' ');
                        runGameId.append(vrcArgs.get(i));
                    }
                }
                LOG.info("Requesting VRChat launch through Steam URI; launch arguments are private");
                ProcessBuilder builder = privateOutput(new ProcessBuilder("steam", runGameId.toString()));
                guard.check();
                return builder.start();
            }
            catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception ex)
            {
                LOG.warn("Steam URI process could not be started; falling back to the desktop handler ({})", ex.getClass().getSimpleName());
            }
        }
        else
        {
            LOG.warn("steam binary not found in PATH, falling back to URI handler");
        }

        // Strategy 3: original xdg-open / URI handler fallback
        if (vrchatUri != null)
        {
            LOG.info("Requesting VRChat launch through the desktop URI handler; ownership may remain unverified");
            ProcessBuilder builder = privateOutput(new ProcessBuilder("xdg-open", vrchatUri));
            guard.check();
            return builder.start();
        }
        return null; // A desktop/Steam singleton handoff is not proof of child ownership.
    }
    static Process launch_win(String userId, String location, String shortName, LaunchMode mode, ManagedVrcClient.LaunchGuard guard) throws Exception
    {
        String path;
        {
            IntByReference pType = new IntByReference(),
                    pcbData = new IntByReference(256);
             byte[] buffer = new byte[256];
             int werr = Advapi32.INSTANCE.RegGetValue(WinReg.HKEY_CURRENT_USER, "Software\\VRChat", "", Advapi32.RRF_RT_REG_SZ, pType, buffer, pcbData);
             if (werr == WinError.ERROR_MORE_DATA)
             {
                 buffer = new byte[pcbData.getValue()];
                 werr = Advapi32.INSTANCE.RegGetValue(WinReg.HKEY_CURRENT_USER, "Software\\VRChat", "", Advapi32.RRF_RT_REG_SZ, pType, buffer, pcbData);
             }
             if (werr != WinError.ERROR_SUCCESS)
                 throw new Exception(String.format("Failed to locate VRChat via registry: 0x%08x", werr));
             int regLen = pcbData.getValue() - 2;
             if (regLen <= 0)
                 throw new Exception("Failed to locate VRChat via registry: empty value");
             path = new String(buffer, 0, regLen, StandardCharsets.UTF_16LE);
        }
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(new File(path, "launch.exe").getAbsolutePath());
        cmd.addAll(clientArguments(location, shortName, mode));

        LOG.info("Requesting VRChat launch through its registered launcher; launch arguments are private");
        ProcessBuilder builder = privateOutput(new ProcessBuilder(cmd));
        guard.check();
        return builder.start();
    }

    /** Launchers can echo secure instance arguments; do not inherit their output. */
    static ProcessBuilder privateOutput(ProcessBuilder builder)
    {
        return builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
    }

}
