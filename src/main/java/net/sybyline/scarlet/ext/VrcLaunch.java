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
        if (mode == null)
            mode = LaunchMode.DESKTOP;
        String vrchatUri = buildLaunchUri(location, shortName);

        // Build the VRChat launch arguments (mirrors what launch_win passes on Windows)
        java.util.List<String> vrcArgs = new java.util.ArrayList<>();
        if (mode.noVr) { vrcArgs.add("--no-vr"); }
        vrcArgs.add("--enable-sdk-log-levels");
        vrcArgs.add("--enable-udon-debug-logging");
        vrcArgs.add("--enable-verbose-logging");
        vrcArgs.add("--log-debug-levels=API;All;Always;AssetBundleDownloadManager;ContentCreator;Errors;NetworkData;NetworkProcessing;NetworkTransport;Warnings");
        if (location != null) { vrcArgs.add(vrchatUri); }
        if (userId   != null) { vrcArgs.add("--profile=" + userId); }

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
        if (mode == null)
            mode = LaunchMode.DESKTOP;
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

        String vrchatUri = buildLaunchUri(location, shortName);
        if (vrchatUri != null)
        {
            cmd.add(vrchatUri);
        }

        if (userId != null)
        {
            cmd.add("--profile=" + userId);
        }

        if (mode.noVr)
        {
            cmd.add("--no-vr");
        }

        cmd.add("--enable-sdk-log-levels");
        cmd.add("--enable-udon-debug-logging");
        cmd.add("--enable-verbose-logging");
        cmd.add("--log-debug-levels=API;All;Always;AssetBundleDownloadManager;ContentCreator;Errors;NetworkData;NetworkProcessing;NetworkTransport;Warnings");

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
