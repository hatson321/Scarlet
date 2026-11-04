package net.sybyline.scarlet.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.jar.Manifest;


import net.sybyline.scarlet.Scarlet;

public class MavenDepsLoader
{
    static final boolean ALLOW_RUNTIME_DOWNLOADS = Boolean.getBoolean("scarlet.allowRuntimeDependencyDownloads");

    public static void init()
    {
        // noop
    }

    public static Path jarPath() // returns null if not running from a jar
    {
        return jarPath;
    }

    private MavenDepsLoader()
    {
        throw new UnsupportedOperationException();
    }

    static final String[] repos =
    {
        // Runtime downloads are disabled by default. If explicitly enabled,
        // only recover artifacts from Maven Central; lower-trust build-on-demand
        // repositories are intentionally excluded from this path.
        "https://repo1.maven.org/maven2/",
    };

    static Path jarPath = null;

    static
    {
        clinit();
    }

    static void clinit()
    {
        Path jarPath;
        URL url;
        try
        {
            URL owner = MavenDepsLoader.class.getProtectionDomain().getCodeSource().getLocation();
            jarPath = owningJar(owner);
            if (jarPath == null) return;
            // Resolve this class's own archive, not the first dependency manifest on the classpath.
            url = new URL("jar:" + jarPath.toUri().toASCIIString() + "!/META-INF/MANIFEST.MF");
        }
        catch (Exception ex)
        {
            System.err.println("Scarlet/MavenDepsLoader: Cannot resolve the owning JAR path");
            return;
        }
        Path jarDir = jarPath.getParent(),
             depsDir = jarDir.resolve("libraries");
        
        MavenDepsLoader.jarPath = jarPath;
        
        if (!Files.isDirectory(depsDir)) try
        {
            Files.createDirectories(depsDir);
        }
        catch (Exception ex)
        {
            System.err.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Exception creating dependencies directory '%s'", depsDir));
            ex.printStackTrace(System.err);
            return;
        }
        
        Manifest mf;
        try (InputStream mfIn = url.openStream())
        {
            mf = new Manifest(mfIn);
        }
        catch (Exception ex)
        {
            System.err.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Exception reading manifest from '%s'", url));
            ex.printStackTrace(System.err);
            return;
        }
        
        String cpEntriesString = mf.getMainAttributes().getValue("Class-Path");
//        System.out.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Class-Path: %s", cpEntriesString));
        if (cpEntriesString == null || cpEntriesString.trim().isEmpty())
            return;
        
        String[] cpEntries = cpEntriesString.split(" ");
        
        String libPrefix = "libraries/";
        
        for (String cpEntry : cpEntries)
        {
            if (cpEntry.startsWith(libPrefix))
            {
//                System.out.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Checking dependency '%s'", cpEntry));
                dlDep(depsDir, cpEntry.substring(libPrefix.length()));
            }
        }
        
    }

    static Path owningJar(URL owner) throws Exception
    {
        if (owner == null) return null;
        if ("jar".equals(owner.getProtocol()))
            owner = ((JarURLConnection)owner.openConnection()).getJarFileURL();
        if (!"file".equals(owner.getProtocol())) return null;
        Path path = Paths.get(owner.toURI()).toAbsolutePath().normalize();
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar") ? path : null;
    }

    static void dlDep(Path depsDir, String line)
    {
        if ((line = line.trim()).isEmpty())
            return;
        if (line.charAt(0) == '#')
            return;
        if (".".equals(line))
            return;
        
        String depName = Paths.get(line).getFileName().toString();
        
        Path depPath = depsDir.resolve(line);
        if (Files.isRegularFile(depPath))
            return;
        if (!ALLOW_RUNTIME_DOWNLOADS)
        {
            System.err.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Refusing to download missing dependency '%s' because runtime downloads are disabled. Start with -Dscarlet.allowRuntimeDependencyDownloads=true to allow this.", depName));
            return;
        }
        
        String depUrl = findDepUrl(line);
        
        if (depUrl == null)
        {
            System.err.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Failed to locate dependency '%s' from '%s' to '%s'", depName, depUrl, depPath));
            return;
        }
        
        Path depParent = depPath.getParent();
        if (!Files.isDirectory(depParent))
        try
        {
            Files.createDirectories(depParent);
        }
        catch (Exception ex)
        {
            System.err.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Exception creating directories '%s'", depPath.getParent()));
            ex.printStackTrace(System.err);
            return;
        }
        
        try
        {
            String expectedSha256 = fetchRemoteChecksum(depUrl, ".sha256");
            if (expectedSha256 == null)
            {
                System.err.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Refusing to download dependency '%s' because no remote SHA-256 checksum was available at '%s.sha256'", depName, depUrl));
                return;
            }
            try (HttpURLInputStream depIn = HttpURLInputStream.get(depUrl, HttpURLInputStream.PUBLIC_ONLY))
            {
            Path tmp = Files.createTempFile(depParent, depName, ".part");
            try
            {
                Files.copy(depIn, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!expectedSha256.equalsIgnoreCase(sha256Hex(tmp)))
                    throw new SecurityException("Downloaded dependency checksum mismatch for " + depName);
                if (!looksLikeSha256Path(line) && !verifyDependencyDigest(depPath, tmp))
                    throw new SecurityException("Existing dependency digest mismatch for " + depName);
                Files.move(tmp, depPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            finally
            {
                Files.deleteIfExists(tmp);
            }
            System.out.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Located and copied dependency '%s' from '%s' to '%s'", depName, depUrl, depPath));
            }
        }
        catch (Exception ex)
        {
            System.err.println(String.format("Hatson321/Scarlet Logger/MavenDepsLoader: Exception copying dependency '%s' from '%s' to '%s'", depName, depUrl, depPath));
            ex.printStackTrace(System.err);
        }
    }

    static String findDepUrl(String path)
    {
        for (String repo : repos)
        {
            String url = repo + path;
            try
            {
                URL url0 = new URL(url);
                HttpURLConnection connection = (HttpURLConnection)url0.openConnection();
                try
                {
                    connection.setRequestMethod("HEAD");
                    connection.setRequestProperty("User-Agent", Scarlet.USER_AGENT);
                    connection.setConnectTimeout(5_000);
                    connection.setReadTimeout(5_000);
                    int code = connection.getResponseCode();
                    if (code >= 200 && code <= 399)
                    {
                        return url;
                    }
                }
                finally
                {
                    connection.disconnect();
                }
            }
            catch (Exception ex)
            {
                // noop
            }
        }
        return null;
    }

    static String fetchRemoteChecksum(String depUrl, String suffix)
    {
        String checksumUrl = depUrl + suffix;
        try (HttpURLInputStream in = HttpURLInputStream.get(checksumUrl, HttpURLInputStream.PUBLIC_ONLY))
        {
            String text = new String(MiscUtils.readAllBytes(in), StandardCharsets.UTF_8).trim();
            if (text.isEmpty())
                return null;
            int whitespace = text.indexOf(' ');
            if (whitespace > 0)
                text = text.substring(0, whitespace);
            whitespace = text.indexOf('\t');
            if (whitespace > 0)
                text = text.substring(0, whitespace);
            return text.matches("(?i)[0-9a-f]{64}") ? text.toLowerCase() : null;
        }
        catch (Exception ex)
        {
            return null;
        }
    }
    static boolean verifyDependencyDigest(Path existing, Path downloaded)
    {
        try
        {
            if (!Files.isRegularFile(existing))
                return true;
            return java.util.Arrays.equals(sha256(existing), sha256(downloaded));
        }
        catch (Exception ex)
        {
            return false;
        }
    }
    static boolean looksLikeSha256Path(String path)
    {
        return path != null && path.endsWith(".sha256");
    }
    static byte[] sha256(Path path) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path))
        {
            byte[] buf = new byte[8192];
            for (int read; (read = in.read(buf)) >= 0;)
                digest.update(buf, 0, read);
        }
        return digest.digest();
    }
    static String sha256Hex(Path path) throws Exception
    {
        byte[] digest = sha256(path);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

}
