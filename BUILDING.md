# Building Scarlet from source

## Choose the matching source

As of November 1, 2026, the published stable release is **0.6.0** and the latest
published preview is **0.6.1-a11**. Repository `main` uses the **0.6.0 stable
application baseline**. The plain **0.6.1** release is still a draft, and
**0.6.1-a12** is separate local release preparation, not the version on `main` or
a published release.

To rebuild a published version, use its attached `scarlet-<version>-source.zip`
and verify it against that release's `SHA256SUMS.txt` on the
[Scarlet releases page](https://github.com/Hatson321/Scarlet/releases). This is the
frozen source snapshot prepared for the matching JAR. GitHub's **Code → Download
ZIP** follows the selected branch, and the automatically generated tag archives
are not a substitute for the attached release source archive.

The default GitHub branch is based on the frozen 0.6.0 release source, with
refreshed documentation and the approved Help → Credits cleanup. These overlays
mean the branch is not byte-for-byte identical to the original release archive.
The accompanying feature reference remains labeled for the a11 preview; it does
not change the stable application code. Use the matching attached archive for
exact release-source reproduction and the entrypoints described here.

Historical mobile/Android sources already on `main` are retained unchanged for
history, but the two legacy Java integration classes are explicitly excluded
from the desktop compiler. The obsolete Android build workflow is no longer
active. Retaining these files does not restore mobile support or add a mobile
artifact to the stable desktop build.

Run the commands below from the extracted source directory containing `pom.xml`,
`scripts/` and `libdave-maven/`. If you only want to run Scarlet, download the
standard release JAR instead; Java is not bundled. Use Java 11 or newer, preferably
Java 21. Building the application and its tests requires JDK 21.

## Supported maintainer toolchain

| Component | Supported requirement |
|---|---|
| Linux | glibc-based x86-64 or AArch64 distribution with glibc 2.38 or newer |
| Linux C++ runtime | `libstdc++.so.6` exporting `GLIBCXX_3.4.32` and `CXXABI_1.3.9`; `libgcc_s.so.1` exporting `GCC_3.0` |
| Build JDK | **21** |
| Runtime Java | **11 or newer**; Java 21 is the supported maintainer/runtime recommendation |
| Maven | 3.9 or newer |
| POSIX/native inspection tools | `/bin/sh`, `uname`, `sed`, `awk`, `tr`, `readelf` (binutils), and `ldconfig` |
| Network | required on the first build for Maven dependencies and the checksum-pinned upstream VRChat SDK archive from GitHub |

The automated Linux build is pinned to Ubuntu 24.04 and Temurin JDK 21. The
repository check rejects the wrong Java major, Maven older than 3.9, non-Linux
hosts, unsupported CPU families, confirmed glibc versions older than 2.38,
and missing host libraries or versioned exports required by the bundled fallback DAVE library.
This keeps maintainer builds aligned with the environment exercised by CI.

Scarlet's own classes still target Java 8 bytecode, but the shaded Junidecode
dependency contains base classes at class-file major version 55 (Java 11).
Therefore the complete runnable JAR requires Java 11 or newer. The package
verifier scans every non-multi-release class and fails if that documented
runtime floor drifts.

No C/C++ compiler is needed for a normal desktop build. Scarlet packages the
prebuilt DAVE libraries from `moe.kyokobot.libdave:natives-*:0.1.2`, and the
post-build verifier checks that the shaded JAR contains correctly identified
libdave-jvm and fallback DAVE ELF payloads for Linux x86-64, plus the fallback
payload for AArch64.

### Linux voice runtime requirements

| Packaged path | Architecture | Declared native requirements |
|---|---|---|
| Preferred JNI `natives/linux-x86-64/libdave-jvm.so` | x86-64 | glibc symbols through 2.25; libc, libm, libpthread, and the x86-64 glibc loader |
| Fallback `linux-x86-64/libdave.so` | x86-64 | glibc through 2.38; libstdc++ with GLIBCXX through 3.4.32 and CXXABI through 1.3.9; libgcc_s with GCC_3.0 |
| Fallback `linux-aarch64/libdave.so` | AArch64 | same C/C++ version requirements as the x86-64 fallback, plus the AArch64 glibc loader |

The supported maintainer baseline includes fallback requirements even if the
preferred x86-64 JNI path currently loads. AArch64 uses the bundled fallback:
the package does not contain a preferred AArch64 JNI DAVE library.

The preflight reads the loader cache and ELF version-definition tables for the
**host architecture**. It does not execute the packaged native libraries or
install anything. The archive verifier independently parses both fallback
ELF version-needs sections and fails if the recorded C++ ABI floor drifts.
Passing either check is not proof of successful native loading, Discord voice,
or another architecture's runtime compatibility. Native-load and live voice
tests remain release-acceptance checks on actual Windows/Linux targets.

Use distribution-provided compatible glibc, C++ and GCC runtime packages.
Do not copy random shared libraries into system directories or replace the
system loader to satisfy a version error; use a compatible supported distribution.

> **PowerPC status:** the PowerPC edition remains experimental and is not part
> of the supported Linux verification gate. Do not treat a successful x86-64
> or AArch64 package check as PowerPC runtime validation. No PowerPC JAR is attached
> to the published 0.6.0 or 0.6.1-a11 release; it is an experimental source-build output.

## Install the prerequisites on Linux

Install a JDK 21 distribution and Apache Maven 3.9 or newer using packages from
your distribution or the vendors' official installers. Confirm the result from
the repository root:

```sh
sh scripts/linux/check-toolchain.sh
```

The check does not install packages or invoke `sudo`. This is deliberate: it
prints each detected version and fails safely, leaving package-manager and
privilege decisions to the maintainer. Maven dependencies are stored under
`.build-cache/maven/` inside this checkout rather than in a user-wide Maven
repository.

## Reproducible Linux build and package check

Run the single maintainer entry point from the repository root:

```sh
sh scripts/linux/build-and-verify.sh
```

It performs these steps in order:

1. validates Linux, CPU, glibc, host C/C++ runtime exports, JDK, `javac`, `jar`, and Maven;
2. verifies and builds the pinned, unmodified upstream VRChat SDK source;
3. builds and installs all vendored `libdave-maven/` modules into the
   workspace-local Maven repository;
4. runs Scarlet's full Maven `verify` lifecycle (including tests when present);
5. validates the main, debug, and experimental PowerPC JAR manifests, plus the
   packaged PowerPC ELF identity without claiming PowerPC runtime support;
6. verifies the supported Linux x86-64 and AArch64 native payload names, ELF
   class, byte order, machine type, fallback C++ ABI requirements, ZIP safety, duplicate-entry absence, and
   the Java 11 base-class runtime floor;
7. writes `target/SHA256SUMS-linux.txt` for the inspected distribution JARs.

After a successful online build, repeat it without network access to prove the
workspace cache is complete:

```sh
SCARLET_MAVEN_OFFLINE=1 sh scripts/linux/build-and-verify.sh
```

Set `SCARLET_MAVEN_REPOSITORY` only when you intentionally need a different
repository directory. The default remains inside this checkout.

## Windows source build

Open a new PowerShell window with JDK 21 and Maven 3.9+ on `PATH`, then run
from this source directory:

```powershell
& .\scripts\windows\build-and-verify.ps1
```

The script uses the same SDK bootstrap, vendored libdave sources, root Maven
tests and static package verifier as the Linux build. It does not start
Scarlet, connect accounts, launch a game, install Java, or alter OS preferences.
Root tests use an in-memory Java preferences provider. An offline repeat uses
`-Offline` after the dependency cache has been populated. Static inspection of
the Linux native payloads on Windows is not Linux runtime validation.

The tests require a filesystem that really enforces owner-only ACLs or POSIX
permissions. Some virtual/shared drives report permission support but ignore
changes; the migration safety tests must fail on those drives. On Windows,
create a dedicated empty test directory on a supported local drive and pass
`-TestDirectory 'C:\path\to\dedicated-scarlet-tests'` to the script. This
changes only synthetic test storage, not the source or build-output location.
The Maven equivalent is `-Dscarlet.test.root=<absolute-existing-test-directory>`.
Never point this option at real Scarlet data. Test-only Java compilation uses
JDK 21; it does not raise the application's Java 11 runtime requirement.

## Pinned VRChat SDK source dependency

The SDK version remains **1.23.0**. Its previously declared JitPack download
was unavailable when the source-build bootstrap was introduced. Both build entrypoints now fetch the
upstream source archive, verify its SHA-256, and build its unmodified POM using
the upstream coordinates `io.github.vrchatapi:vrchatapi:1.23.0` locally. They do
not extract classes from an older Scarlet JAR or need a private Maven cache.

- Source: `https://github.com/vrchatapi/vrchatapi-java/archive/refs/tags/1.23.0.zip`
- Upstream commit: `a5fef92cb430a1dd6bacd4133b4046d7d35d2b4e`
- Archive SHA-256: `34e70212f1b0069bc9e406005d59fc7d245e24ad1027a5cfbde4410d6e8b9dd7`
- Cache: `.build-cache/vrchat-sdk/vrchatapi-java-1.23.0-source.zip`

`java scripts/BootstrapVrchatSdk.java --offline` verifies an already cached
archive and the extracted upstream files without downloading anything. A
checksum mismatch or modified cached source is an error, not a reason to use
another SDK version silently. Upstream SDK tests and Javadoc are skipped in
this dependency-build step; Scarlet's own tests are not skipped. Existing
license and attribution files remain untouched.

This is a repeatable source build process, not a claim of bit-for-bit identical
JAR timestamps across different machines. The final release checksums identify
the actual inspected artifacts.

## Why libdave has to be built first

Scarlet depends on three artifacts at version `1.0.0`:

- `moe.kyokobot.libdave:libdave-jvm-api`
- `moe.kyokobot.libdave:libdave-jvm-impl`
- `moe.kyokobot.libdave:libdave-jvm-adapter-jda`

**These are not published to any public Maven repository.** Their sources are
vendored in `libdave-maven/`. The committed build script passes the same
`-Dmaven.repo.local` value to both builds, so `mvn install` places them in
`.build-cache/maven/` by default and the root build resolves them from there.
Set `SCARLET_MAVEN_REPOSITORY` to give both steps a different repository path.

Skip that step and the build fails like this:

```
[ERROR] Failed to read artifact descriptor for
        moe.kyokobot.libdave:libdave-jvm-adapter-jda:jar:1.0.0
[ERROR]   Caused by: ... could not be resolved: ... from/to jitpack.io
          (https://jitpack.io): status code: 401, reason phrase: Unauthorized (401)
```

For this observed missing vendored artifact, Maven reported a JitPack `401`
after local resolution failed. Install the three vendored modules into the same
Maven repository used by the root build. That observation does not mean every
JitPack `401` has this cause: inspect the failing artifact and repository first.
The supported build uses the pinned SDK bootstrap above and does not require a
JitPack account or token; unrelated network or authentication failures need their
own diagnosis.

## Build outputs

All in `target/`:

| Artifact | Purpose |
|---|---|
| `scarlet-<version>.jar` | **the one you want** — shaded, desktop natives only |
| `scarlet-<version>-debug.jar` | diagnostic build with the in-app Debug menu enabled; not the normal release download |
| `scarlet-<version>-ppc.jar` | experimental PowerPC edition; not in the supported Linux gate |
| `original-scarlet-<version>.jar` | pre-shade jar; not runnable on its own |
| `SHA256SUMS-linux.txt` | SHA-256 checksums written by the Linux package verifier |
| `SHA256SUMS-packages.txt` | SHA-256 checksums written by the Windows package verifier |

The current source build produces no Android application or mobile companion.
The published desktop assets are the standard Windows/Linux JAR, matching source
ZIP and checksum file; building debug or PowerPC outputs does not publish them.

Run with:

```sh
java -jar target/scarlet-<version>.jar
```

## Troubleshooting

**`401 Unauthorized` from jitpack.io** — identify the failing artifact first.
For a missing `moe.kyokobot.libdave` module at `1.0.0`, verify the local vendored
install and shared Maven repository path. For the VRChat SDK, use the pinned
bootstrap rather than assuming the libdave step fixes it. Other network or
authentication failures are not explained solely by the HTTP status code.

**Still failing after installing libdave** — Maven caches resolution failures
and will not retry until they expire. Clear them:

```sh
maven_repository=${SCARLET_MAVEN_REPOSITORY:-"$PWD/.build-cache/maven"}
find "$maven_repository/moe/kyokobot/libdave" -name "*.lastUpdated" -delete
```

**Verify libdave installed correctly:**

```sh
maven_repository=${SCARLET_MAVEN_REPOSITORY:-"$PWD/.build-cache/maven"}
ls "$maven_repository"/moe/kyokobot/libdave/*/1.0.0/*.jar
```

You should see the three jars listed above. If the directories contain only `.lastUpdated`
files, the install did not happen.

**`check-toolchain.sh` says the host is not Linux** — use a native Linux
machine, a Linux virtual machine, or a CI runner. Package inspection on another
operating system is useful but does not prove that the Linux native library can
be loaded at runtime.

**The host uses musl rather than glibc** — the supported prebuilt DAVE payloads
target glibc. Use a supported glibc-based distribution for release validation.

**`No tests to run` appears for a vendored libdave module** — those modules do
not currently contain test sources. The root Scarlet build does contain tests;
if Maven reports no root tests, confirm that `src/test/` is present and that the
full `verify` lifecycle is running.
