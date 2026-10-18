import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies the supported Linux payloads in Scarlet's shaded distribution JARs.
 * Run with JDK 21 source-file mode; no third-party verification dependency is
 * required.
 */
public final class VerifyLinuxPackage {
    private static final String MAIN_CLASS = "net.sybyline.scarlet.Scarlet";
    private static final String MAIN_CLASS_ENTRY = "net/sybyline/scarlet/Scarlet.class";
    private static final String LINUX_X86_64 = "linux-x86-64/libdave.so";
    private static final String LINUX_AARCH64 = "linux-aarch64/libdave.so";
    private static final String LINUX_PPC = "linux-ppc/libdave.so";
    private static final String DAVE_JVM_X86_64 = "natives/linux-x86-64/libdave-jvm.so";
    private static final Pattern GLIBC_SYMBOL = Pattern.compile("GLIBC_([0-9]+)[.]([0-9]+)(?:[.][0-9]+)?");

    private int failures;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: java VerifyLinuxPackage.java <main.jar> <debug.jar> <ppc.jar> <checksums.txt>");
            System.exit(2);
        }

        VerifyLinuxPackage verifier = new VerifyLinuxPackage();
        List<Path> artifacts = new ArrayList<>();
        artifacts.add(Path.of(args[0]));
        artifacts.add(Path.of(args[1]));
        artifacts.add(Path.of(args[2]));

        verifier.verifyDistribution(artifacts.get(0), false, false);
        verifier.verifyDistribution(artifacts.get(1), true, false);
        verifier.verifyDistribution(artifacts.get(2), false, true);
        verifier.writeChecksums(artifacts, Path.of(args[3]));

        System.out.printf("Verification summary: %d failure(s).%n", verifier.failures);
        if (verifier.failures != 0) {
            System.exit(1);
        }
    }

    private void verifyDistribution(Path artifact, boolean expectDebug, boolean expectPowerPcEdition) {
        System.out.printf("%nInspecting %s%n", artifact.toAbsolutePath().normalize());
        if (!Files.isRegularFile(artifact)) {
            fail("Distribution JAR is missing: " + artifact);
            return;
        }

        try (JarFile jar = new JarFile(artifact.toFile(), true)) {
            verifyEntryNames(jar);
            verifyManifest(jar.getManifest(), expectDebug, expectPowerPcEdition);
            requireEntry(jar, MAIN_CLASS_ENTRY);
            verifyBaseClassVersion(jar);
            verifyElfEntry(jar, DAVE_JVM_X86_64, 2, 1, 62, true, 2, 25);
            verifyElfEntry(jar, LINUX_X86_64, 2, 1, 62, true, 2, 38);
            verifyElfEntry(jar, LINUX_AARCH64, 2, 1, 183, true, 2, 38);

            JarEntry powerPcNative = jar.getJarEntry(LINUX_PPC);
            if (expectPowerPcEdition) {
                verifyElfEntry(jar, LINUX_PPC, 1, 2, 20, true, null, null);
            } else if (powerPcNative != null) {
                fail("Supported desktop JAR unexpectedly contains experimental payload " + LINUX_PPC);
            } else {
                pass("Experimental PowerPC native is absent from the supported desktop JAR");
            }
        } catch (IOException exception) {
            fail("Could not inspect " + artifact + ": " + exception.getMessage());
        }
    }

    private void verifyEntryNames(JarFile jar) {
        Set<String> names = new HashSet<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!names.add(name)) {
                fail("Duplicate JAR entry: " + name);
            }
            if (name.startsWith("/") || name.startsWith("\\") || name.contains("../") || name.contains("..\\")) {
                fail("Unsafe JAR entry path: " + name);
            }
        }
        pass("JAR paths are relative, traversal-free, and unique (" + names.size() + " entries)");
    }

    private void verifyManifest(Manifest manifest, boolean expectDebug, boolean expectPowerPcEdition) {
        if (manifest == null) {
            fail("JAR manifest is missing");
            return;
        }
        Attributes attributes = manifest.getMainAttributes();
        expectAttribute(attributes, Attributes.Name.MAIN_CLASS.toString(), MAIN_CLASS);
        expectAttribute(attributes, "Scarlet-Debug", expectDebug ? "true" : null);
        expectAttribute(attributes, "Scarlet-Edition", expectPowerPcEdition ? "ppc" : null);
    }

    private void expectAttribute(Attributes attributes, String name, String expected) {
        String actual = attributes.getValue(name);
        if (expected == null) {
            if (actual == null) {
                pass("Manifest omits " + name + " as expected");
            } else {
                fail("Manifest attribute " + name + " should be absent but is " + actual);
            }
        } else if (expected.equals(actual)) {
            pass("Manifest " + name + " = " + expected);
        } else {
            fail("Manifest attribute " + name + " is " + actual + "; expected " + expected);
        }
    }

    private void requireEntry(JarFile jar, String entryName) {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null || entry.isDirectory() || entry.getSize() == 0) {
            fail("Required JAR entry is missing or empty: " + entryName);
        } else {
            pass("Required JAR entry is present: " + entryName + " (" + entry.getSize() + " bytes)");
        }
    }

    private void verifyBaseClassVersion(JarFile jar) {
        int highestMajor = -1;
        String highestEntry = null;
        int classCount = 0;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/versions/")) {
                continue;
            }
            classCount++;
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] header = input.readNBytes(8);
                if (header.length != 8
                        || (header[0] & 0xff) != 0xca
                        || (header[1] & 0xff) != 0xfe
                        || (header[2] & 0xff) != 0xba
                        || (header[3] & 0xff) != 0xbe) {
                    fail("Invalid class-file header: " + name);
                    continue;
                }
                int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
                if (major > highestMajor) {
                    highestMajor = major;
                    highestEntry = name;
                }
            } catch (IOException exception) {
                fail("Could not inspect class-file version for " + name + ": " + exception.getMessage());
            }
        }

        if (highestMajor == 55) {
            pass("Highest base class-file major is 55 (Java 11) across " + classCount
                + " classes; example: " + highestEntry);
        } else {
            fail("Highest base class-file major is " + highestMajor
                + "; documentation expects Java 11 class-file major 55 (example: " + highestEntry + ")");
        }
    }

    private void verifyElfEntry(JarFile jar, String entryName, int elfClass, int byteOrder,
                                int machine, boolean required, Integer glibcMajor, Integer glibcMinor) {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) {
            if (required) {
                fail("Required Linux native is missing: " + entryName);
            }
            return;
        }
        if (entry.isDirectory() || entry.getSize() < 20) {
            fail("Linux native is empty or too short for an ELF header: " + entryName);
            return;
        }

        try (InputStream input = jar.getInputStream(entry)) {
            byte[] payload = input.readAllBytes();
            if (payload.length < 20
                    || (payload[0] & 0xff) != 0x7f
                    || payload[1] != 'E'
                    || payload[2] != 'L'
                    || payload[3] != 'F') {
                fail("Native payload is not an ELF file: " + entryName);
                return;
            }

            int actualClass = payload[4] & 0xff;
            int actualByteOrder = payload[5] & 0xff;
            int actualMachine;
            if (actualByteOrder == 1) {
                actualMachine = (payload[18] & 0xff) | ((payload[19] & 0xff) << 8);
            } else if (actualByteOrder == 2) {
                actualMachine = ((payload[18] & 0xff) << 8) | (payload[19] & 0xff);
            } else {
                fail("ELF payload has an unknown byte order: " + entryName);
                return;
            }

            if (actualClass == elfClass && actualByteOrder == byteOrder && actualMachine == machine) {
                pass("ELF identity is correct: " + entryName + " (" + entry.getSize() + " bytes)");
            } else {
                fail("ELF identity mismatch for " + entryName + ": class=" + actualClass
                    + ", byteOrder=" + actualByteOrder + ", machine=" + actualMachine);
            }

            if (glibcMajor != null && glibcMinor != null) {
                verifyGlibcRequirement(entryName, payload, glibcMajor, glibcMinor);
            }
            if (LINUX_X86_64.equals(entryName) || LINUX_AARCH64.equals(entryName)) {
                verifyCppRequirements(entryName, payload);
            }
        } catch (IOException exception) {
            fail("Could not read native payload " + entryName + ": " + exception.getMessage());
        }
    }

    private void verifyCppRequirements(String entryName, byte[] payload) {
        try {
            Map<String, Set<String>> requirements = elfVersionNeeds(payload);
            Set<String> cpp = requirements.getOrDefault("libstdc++.so.6", Set.of());
            Set<String> gcc = requirements.getOrDefault("libgcc_s.so.1", Set.of());
            String highestCpp = cpp.stream().filter(value -> value.startsWith("GLIBCXX_"))
                .max(VerifyLinuxPackage::compareSymbolVersions).orElse("missing");
            String highestAbi = cpp.stream().filter(value -> value.startsWith("CXXABI_"))
                .max(VerifyLinuxPackage::compareSymbolVersions).orElse("missing");
            if (!"GLIBCXX_3.4.32".equals(highestCpp) || !"CXXABI_1.3.9".equals(highestAbi) || !gcc.contains("GCC_3.0")) {
                fail("Fallback native ABI requirement drift for " + entryName + ": " + requirements
                    + "; update and revalidate the documented Linux platform matrix before release");
            } else {
                pass("ELF version-needs confirm " + entryName + ": libstdc++.so.6 " + highestCpp
                    + "/" + highestAbi + ", libgcc_s.so.1 GCC_3.0 (static inspection, not runtime loading)");
            }
        } catch (IOException | RuntimeException malformed) {
            fail("Could not validate native ABI requirements for " + entryName + ": " + malformed.getMessage());
        }
    }

    private static int compareSymbolVersions(String first, String second) {
        String[] left = first.substring(first.indexOf('_') + 1).split("[.]");
        String[] right = second.substring(second.indexOf('_') + 1).split("[.]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int difference = Integer.compare(i < left.length ? Integer.parseInt(left[i]) : 0,
                i < right.length ? Integer.parseInt(right[i]) : 0);
            if (difference != 0) return difference;
        }
        return 0;
    }

    /** Parse actual ELF64 little-endian GNU version-needs, not arbitrary embedded strings. */
    static Map<String, Set<String>> elfVersionNeeds(byte[] payload) throws IOException {
        if (payload.length < 64 || payload[4] != 2 || payload[5] != 1)
            throw new IOException("Expected ELF64 little-endian payload");
        ByteBuffer bytes = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long sections = bytes.getLong(40);
        int size = Short.toUnsignedInt(bytes.getShort(58)), count = Short.toUnsignedInt(bytes.getShort(60));
        if (size < 64 || count == 0) throw new IOException("Invalid ELF section table");
        checkedRange(payload, sections, (long) size * count);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int section = (int) (sections + (long) i * size);
            if (bytes.getInt(section + 4) != 0x6ffffffe) continue; // SHT_GNU_verneed
            long start = bytes.getLong(section + 24), length = bytes.getLong(section + 32);
            checkedRange(payload, start, length);
            int stringIndex = bytes.getInt(section + 40);
            if (stringIndex < 0 || stringIndex >= count) throw new IOException("Invalid ELF string table link");
            int stringsSection = (int) (sections + (long) stringIndex * size);
            long strings = bytes.getLong(stringsSection + 24), stringsLength = bytes.getLong(stringsSection + 32);
            checkedRange(payload, strings, stringsLength);
            for (long node = start; node < start + length;) {
                checkedSectionRange(node, 16, start, length);
                int at = (int) node, auxiliaryCount = Short.toUnsignedInt(bytes.getShort(at + 2));
                String library = elfString(payload, strings, stringsLength, Integer.toUnsignedLong(bytes.getInt(at + 4)));
                Set<String> versions = result.computeIfAbsent(library, ignored -> new LinkedHashSet<>());
                long auxiliary = node + Integer.toUnsignedLong(bytes.getInt(at + 8));
                for (int j = 0; j < auxiliaryCount; j++) {
                    checkedSectionRange(auxiliary, 16, start, length);
                    versions.add(elfString(payload, strings, stringsLength, Integer.toUnsignedLong(bytes.getInt((int) auxiliary + 8))));
                    long next = Integer.toUnsignedLong(bytes.getInt((int) auxiliary + 12));
                    if (j + 1 < auxiliaryCount && next < 16) throw new IOException("Invalid ELF version auxiliary chain");
                    auxiliary += next;
                }
                long next = Integer.toUnsignedLong(bytes.getInt(at + 12));
                if (next == 0) break;
                if (next < 16) throw new IOException("Invalid ELF version-needs chain");
                node += next;
            }
        }
        if (result.isEmpty()) throw new IOException("No ELF version-needs section found");
        return result;
    }

    private static void checkedRange(byte[] bytes, long offset, long size) throws IOException {
        if (offset < 0 || size < 0 || offset > bytes.length || size > bytes.length - offset)
            throw new IOException("ELF range is outside the payload");
    }

    private static void checkedSectionRange(long offset, long size, long start, long length) throws IOException {
        if (offset < start || offset > start + length || size > start + length - offset)
            throw new IOException("ELF version record is outside its section");
    }

    private static String elfString(byte[] bytes, long start, long length, long relative) throws IOException {
        if (relative >= length) throw new IOException("ELF string offset is outside its table");
        int from = (int) (start + relative), end = from;
        while (end < start + length && bytes[end] != 0) end++;
        if (end == start + length) throw new IOException("Unterminated ELF version string");
        return new String(bytes, from, end - from, StandardCharsets.US_ASCII);
    }

    private void verifyGlibcRequirement(String entryName, byte[] payload, int expectedMajor, int expectedMinor) {
        Matcher matcher = GLIBC_SYMBOL.matcher(new String(payload, StandardCharsets.ISO_8859_1));
        int highestMajor = -1;
        int highestMinor = -1;
        while (matcher.find()) {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            if (major > highestMajor || (major == highestMajor && minor > highestMinor)) {
                highestMajor = major;
                highestMinor = minor;
            }
        }

        if (highestMajor == expectedMajor && highestMinor == expectedMinor) {
            pass("Highest required glibc symbol for " + entryName + " is GLIBC_"
                + highestMajor + "." + highestMinor);
        } else if (highestMajor < 0) {
            fail("No GLIBC version symbols found in " + entryName);
        } else {
            fail("Highest required glibc symbol for " + entryName + " is GLIBC_"
                + highestMajor + "." + highestMinor + "; documentation expects GLIBC_"
                + expectedMajor + "." + expectedMinor);
        }
    }

    private void writeChecksums(List<Path> artifacts, Path output) {
        List<String> lines = new ArrayList<>();
        for (Path artifact : artifacts) {
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            try {
                String checksum = sha256(artifact);
                lines.add(checksum + "  " + artifact.getFileName());
                pass("SHA-256 " + artifact.getFileName() + " = " + checksum);
            } catch (IOException | NoSuchAlgorithmException exception) {
                fail("Could not hash " + artifact + ": " + exception.getMessage());
            }
        }

        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(output, lines, StandardCharsets.UTF_8);
            pass("Wrote checksum manifest: " + output.toAbsolutePath().normalize());
        } catch (IOException exception) {
            fail("Could not write checksum manifest " + output + ": " + exception.getMessage());
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private void pass(String message) {
        System.out.println("PASS  " + message);
    }

    private void fail(String message) {
        failures++;
        System.err.println("FAIL  " + message);
    }
}
