# Building Scarlet from source

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
> or AArch64 package check as PowerPC runtime validation.

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
was unavailable during the A12 review. Both build entrypoints now fetch the
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

**JitPack is not the problem here.** It is simply the last repository in the
resolution order, and it answers `401 Unauthorized` — rather than `404` — for
any artifact it does not host. So it takes the blame for an artifact that was
never meant to come from the network at all. No JitPack account or token is
required to build Scarlet.

## Build outputs

All in `target/`:

| Artifact | Purpose |
|---|---|
| `scarlet-<version>.jar` | **the one you want** — shaded, desktop natives only |
| `scarlet-<version>-debug.jar` | same, with debug logging enabled |
| `scarlet-<version>-ppc.jar` | experimental PowerPC edition; not in the supported Linux gate |
| `original-scarlet-<version>.jar` | pre-shade jar; not runnable on its own |
| `SHA256SUMS-linux.txt` | SHA-256 checksums written by the Linux package verifier |

Run with:

```sh
java -jar target/scarlet-<version>.jar
```

## Troubleshooting

**`401 Unauthorized` from jitpack.io** — you skipped the `libdave-maven`
install. See above.

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
