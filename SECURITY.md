# Security & Data Transparency

Scarlet is a self-hostable VRChat group-moderation tool. It runs on your own
machine, signs in to *your* VRChat account and *your* Discord bot, and stores its
data locally. This document is a plain-language inventory of reviewed behavior
that touches the network, your credentials, or your operating system — so you
can verify what it does instead of taking anyone's word for it (including ours).

Nothing here is hidden in the code; this document just collects it in one place.
If you find something that contradicts this document, that's a bug — please report
it (see the bottom).

---

## 1. Network connections

The application and its libraries make outbound connections for the features
below. Some are automatic. This table describes the reviewed categories, when they
happen, and the available controls.

| Destination | Purpose | When | How to disable |
|---|---|---|---|
| `api.vrchat.cloud`, `vrchat.com` | The VRChat API — the entire point of the tool (audit log polling, bans, group management, user lookups). | Continuously, while signed in. | Don't sign in / don't run Scarlet. |
| `discord.com`, `gateway.discord.gg` | Your Discord bot (JDA) — command handling and log posting. | While the bot is connected. | Don't configure a bot token. |
| `raw.githubusercontent.com/<fork>/main/meta.json` | Update & announcement check (reads a small JSON file for the latest build number and any maintainer notice). Read-only; see §4. | Periodically and on manual check. | It only reads a public text file; ignore the notices. |
| `api.github.com`, `jitpack.io`, `repo1.maven.org` | Version discovery for Scarlet and the bundled VRChat API library (which build numbers exist). | On update/version checks. | Cosmetic; failure just means "update check unavailable." |
| Selected avatar-search providers (built-in VRCX-compatible databases or owner-configured public endpoints) | Avatar name search and avatar image/author matching. Requests can contain avatar names and image-file/creator IDs. Services also see the connection IP and a User-Agent containing Scarlet's version, public maintainer support contacts, the configured VRChat group ID and signed-in primary VRChat account ID. These identifiers are filled from this installation, not copied from an example. No VRChat/Discord passwords, tokens or cookies are included. | Off until the owner enables **Allow external avatar lookups and group/account identification**. The new `avatar_search_identified_lookups_enabled` permission defaults to false; older lookup permission is not reused. Once enabled, manual searches, avatar changes, missing-information hydration, and rescans can query the selected providers. | Uncheck permission in **Avatar search providers → Manage**, or deselect every provider. The same selection applies to name, image and author paths. Requests already in flight may finish. |
| `tenor.com` | Fetches a GIF for the `/vrchat-animated-emoji` command. | Only when you run that command with a Tenor URL. | Don't use the command. |
| `vrchat.com/home/group/.../calendar/...` | Builds a direct VRChat calendar-event link for Discord scheduled events. This constructs a URL string; it is **not** a separate data upload. | When posting a scheduled event link. | Don't use event scheduling. |
| Owner-configured advisory translation endpoint | Sends the selected advisory text, target language and optional translation API key to that service. | Only after configuring an endpoint and requesting translation. | Leave the endpoint empty and do not request translation. |
| Maven Central | Retrieves companion JAR dependencies with checksum verification when runtime recovery is explicitly enabled. | Startup dependency recovery only with `-Dscarlet.allowRuntimeDependencyDownloads=true`; disabled by default. | Do not enable that property; normal distributions bundle their dependencies. |
| Distribution/vendor package repositories | Downloads packages through an explicitly approved native-dependency or TTS installer. | Only after the operator chooses installation; a system privilege prompt may follow. | Cancel installation and install prerequisites manually if desired. |

Active avatar-provider lookups use the **public-address filter** with a pinned DNS
result and revalidation at each redirect — see §6. Private/LAN, loopback, link-local,
and other blocked destinations are refused, including custom providers. This does
not claim that every unrelated HTTP client or integration uses the same helper.

Active Scarlet contains no Android/mobile companion, phone-pairing flow, mobile LAN
listener, FCM/OAuth mobile-delivery path, hosted mobile relay, or mobile device registry.
Historical acquisition/release artifacts and quarantined relay evidence may still contain
the removed implementation; those copies are inert evidence and must not be distributed
as future Scarlet builds.

## 2. What can control Scarlet remotely

The principal reviewed command interfaces are:

- **Your Discord bot.** Slash commands are the main remote surface. They come from
  *your* bot in *your* server, and each command is gated by Discord permissions and
  by Scarlet's own permission→role mapping (viewable with `/config-info`). Protect
  the bot token and review permissions; possession of credentials can change this boundary.
- **Local IPC pipe.** A named pipe (`\\.\pipe\ScarletIPC-<groupid>` on Windows,
  `/tmp/ScarletIPC-<groupid>.sock` elsewhere) accepts CLI commands **from the same
  machine only**. It is not a network socket and is not reachable remotely.

## 3. Credential storage

Scarlet stores your VRChat login and 2FA secret so it can re-authenticate
unattended. They are encrypted at rest:

- Cipher: **AES/GCM/NoPadding** (authenticated encryption, 128-bit tag).
- Key derivation: **PBKDF2WithHmacSHA256**.
- Implemented in `util/EncryptedPrefs.java`; correctness (encrypt/decrypt round-trip
  and migration from the legacy key derivation) is covered by
  `util/SecurityRegressionChecks.java`.

VRChat authentication secrets are used for VRChat sign-in over HTTPS. The Discord
bot token is used for Discord, and an explicitly configured translation key is
sent to its selected service. Local exports/backups can contain credential material;
protect those files and do not share them publicly. Encryption does not protect
against malware or another process that can use the same account and key material.

## 4. Update & announcement mechanism

This is the feature most often mistaken for a "backdoor," so here is exactly how it
works.

**Update check.** Scarlet reads a small public file, `meta.json`, on the fork's
`main` branch. It contains the latest build number and an optional `announcement`
text field. The check is **read-only** — it fetches text and, at most, shows you a
notice. It downloads and runs nothing.

**Announcements.** If `meta.json` contains an `announcement` object, Scarlet
displays it as a notice (for example, "VRChat API change expected tomorrow"). It is
a one-way public message board: it is de-duplicated by `id`, can carry an `expires`
timestamp, and **executes no code**. It exists so maintainers can warn running
instances about upstream breakage.

**Applying an update.** The `update-now` command (or the desktop equivalent) does
**not** download or execute a JAR from inside the running process. `Scarlet.update()`:

1. checks that the requested version is a real, known public build;
2. writes that version string to a local file, `scarlet.version.target`;
3. exits with status code `70`.

An **external launcher** (the script/bootstrap you use to start Scarlet) is what
reads `scarlet.version.target`, fetches that published build from the public source,
and relaunches. A plain `restart` (exit code `69`) simply relaunches the **same**
build. This update path records a desired version and exits. Its trust boundary is
the public build source and your
own launcher, both of which you can inspect. Separately, the explicitly enabled
companion-dependency recovery described above can fetch JARs during startup; the
update-check mechanism must not be confused with that opt-in path.

## 5. Process execution

Scarlet launches OS processes only for ordinary desktop tasks:

- Launch VRChat (via Steam or the client executable) — the "open instance" feature.
- Detect whether VRChat is running (`tasklist` on Windows, `pgrep` elsewhere).
- Open a URL or a folder (`xdg-open` / the desktop browse API).
- Relaunch/restart itself (see §4).
- Run a configured local speech engine or initialize Windows SAPI speech.
- Run an operator-approved TTS/native package installation using a local shell,
  PTY, or privilege helper. Installation commands are not an unattended remote-control API.

Native libraries are loaded in the Java process, not as a separate installer process.
Process behavior depends on the enabled feature, platform and local configuration;
inspect command construction before changing trusted installer/provider settings.

## 6. Outbound request safety (SSRF protection)

Fetches using `HttpURLInputStream.PUBLIC_ONLY`, including active avatar-provider
lookups and the reviewed media-download paths, are covered by destination checks
and regression tests. This does not certify every third-party HTTP client or
owner-configured integration. Those checked requests are **rejected** when the target
resolves to:

- loopback (`127.0.0.0/8`, `::1`), private ranges (`10/8`, `172.16/12`,
  `192.168/16`), CGNAT (`100.64/10`), link-local **including the cloud-metadata IP
  `169.254.169.254`**, TEST-NETs, multicast, reserved space, and IPv6 ULA/link-local;
- non-HTTP schemes (`file://`, `javascript:`), opaque URIs, and URLs carrying
  embedded credentials (`user:pass@host`).

The checked helper revalidates redirects and pins its resolved destination to
reduce DNS-rebinding risks. Review separate integrations according to their own
destination policy; this is not a claim that every possible network path is safe.

## 7. Native code & dependencies

Native integration includes preferred JNI libdave-jvm and fallback DAVE voice,
JNA and platform access, pty4j terminal support, IPC support, and Windows COM/SAPI
speech. The selected path depends on the platform and enabled features. Reflection
is used in multiple compatibility/serialization paths, not solely IPC setup.

Runtime companion-dependency downloads are disabled by default. Enabling
`scarlet.allowRuntimeDependencyDownloads` permits checksum-checked downloads from
Maven Central. Checksums do not establish that a publisher or dependency is trustworthy.
Explicit package installation is another code-supply path and requires operator approval.

Dependencies (JDA, the VRChat API library, OkHttp, etc.) are standard, and their
versions are pinned in the build. Both Scarlet's own code and dependencies can
contain defects or supply-chain risks; both need review. Dependency upgrades retain
upstream notices in per-artifact directories under `META-INF/third-party` to prevent
same-named notices from replacing one another in the combined package. Scarlet's
own license is not rewritten by dependency upgrades.

## 8. Why antivirus sometimes flags Scarlet

A Java application that self-relaunches, loads a native library, spawns OS processes,
and reads stored credentials matches several antivirus heuristics for "trojan/
backdoor," even though every one of those behaviors is a documented feature above.
An alert may be a false positive or a genuine problem; this review does not establish
which. Do not dismiss or disable protection solely because a behavior is documented.
Preserve the detection name and file hash, stop using a suspect binary, verify its
provenance, and investigate or submit it to the vendor for review. A source build or
passing tests alone cannot prove the absence of malicious code or vulnerabilities.

## 9. Verifying this yourself

You do not have to trust this document. You can confirm all of it:

1. **Build from source.** Build the JAR from this repository and run that, rather
   than a prebuilt binary. Then the code you audited is the code you run.
2. **Compare against a checksum.** Each release should publish a checksum; verify the
   binary you downloaded matches.
3. **Diff the fork against upstream.** This is a fork of
   `SybylineNetwork/Scarlet`. Diffing against upstream shows exactly what this fork
   changed — the honest answer to "did the fork add something."
4. **Watch the traffic.** Run Scarlet behind a proxy or packet capture and confirm
   the destinations match §1.
5. **Read the sensitive files directly:** `util/EncryptedPrefs.java`,
   `util/HttpURLInputStream.java`, `util/SecurityRegressionChecks.java`, and the
   `update()`/`meta.json` code in `Scarlet.java`.

## 10. Reporting a real vulnerability

If you find a genuine security issue — something this document is wrong about, or a
real hole — please open an issue or contact the maintainer privately rather than
posting an exploit publicly. Concrete reports (a file, a line, a captured request)
can be fixed; rumors cannot.
