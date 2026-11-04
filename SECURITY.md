# Security & Data Transparency

Scarlet is a self-hostable VRChat group-moderation tool. It runs on your own
machine, signs in to *your* VRChat account and *your* Discord bot, and stores its
data locally. This document is a plain-language inventory of reviewed behavior
that touches the network, your credentials, or your operating system — so you
can verify what it does instead of taking anyone's word for it (including ours).

This inventory covers the 0.6.1-a11 application and the current 0.6.1 source line.
Older releases can behave differently; use the source archive matching your JAR
when verifying a specific build. Documentation is not a security certification.
If you find a discrepancy, please report it without sharing secrets (see the bottom).

---

## 1. Network connections

The application and its libraries make outbound connections for the features
below. Some are automatic. **Optional does not mean off by default.** This table
describes the reviewed categories, when they happen, and the available controls;
it is not an exhaustive list of service/CDN hostnames or third-party library behavior.

| Destination | Purpose | When | How to disable |
|---|---|---|---|
| `api.vrchat.cloud`, `vrchat.com` | The VRChat API — the entire point of the tool (audit log polling, bans, group management, user lookups). | Continuously, while signed in. | Don't sign in / don't run Scarlet. |
| `discord.com`, `gateway.discord.gg` | Your Discord bot (JDA) — command handling and log posting. | While the bot is connected. | Don't configure a bot token. |
| `raw.githubusercontent.com/<fork>/main/meta.json` | Update & announcement metadata, including latest versions and maintainer notices. Read-only; see §4. | Automatically at startup and on scheduled checks; also on manual check. | Notification switches hide notices, **not these requests**. There is no in-app switch that disables all metadata polling. |
| `api.github.com`, `jitpack.io` | Version discovery for Scarlet and the VRChat API library. | During automatic or manual version checks. | Hiding update notices is not a network-off setting. Avoid manual checks if unwanted; automatic checks still occur. |
| Selected avatar-search providers (built-in VRCX-compatible databases or owner-configured public endpoints) | Avatar name search and avatar image/author matching. Requests can contain avatar names and image-file/creator IDs. Services also see the connection IP and a User-Agent containing Scarlet's version, public maintainer support contacts, and, when available, the configured VRChat group ID and signed-in primary VRChat account ID. The provider-request code does not attach VRChat/Discord login passwords, tokens or cookies. | Off until the owner enables **Allow external avatar lookups and group/account identification**. The `avatar_search_identified_lookups_consent_v2` permission defaults to false; older lookup permission is not reused. Once enabled, manual searches, avatar changes, missing-information hydration, and rescans can query the selected providers. | Uncheck permission in **Avatar search providers → Manage**, or deselect every provider. The same selection applies to name, image and author paths. Requests already in flight may finish. |
| `tenor.com` | Fetches a GIF for the `/vrchat-animated-emoji` command. | Only when you run that command with a Tenor URL. | Don't use the command. |
| `vrchat.com/home/group/.../calendar/...` | Builds a direct VRChat calendar-event link for Discord scheduled events. This constructs a URL string; it is **not** a separate data upload. | When posting a scheduled event link. | Don't use event scheduling. |
| `api.mymemory.translated.net` | Default free-text translation service. Receives advisory messages and notes from watched groups/users/avatars, moderation-tag descriptions, and the target language. A value supplied in the translation key/email field is sent as the optional email parameter for this backend. | When the operator requests advisory translation with a blank endpoint; English-target requests return locally. Text is sent in HTTPS URL query parameters. | Do not request translation, or configure a trusted LibreTranslate-compatible endpoint instead. **A blank endpoint selects MyMemory; it does not disable translation.** |
| Owner-configured LibreTranslate-compatible endpoint | Receives the same text and target language, plus an optional translation API key. | When the operator requests translation with a nonblank endpoint. | Do not request translation. Review the endpoint operator and transport before sending private text. |
| Maven Central (`repo1.maven.org`) | Retrieves companion JAR dependencies with checksum verification when runtime recovery is explicitly enabled. | Startup dependency recovery only with `-Dscarlet.allowRuntimeDependencyDownloads=true`; disabled by default. | Do not enable that property; normal distributions bundle their dependencies. |
| Distribution/vendor package repositories | Downloads packages through an explicitly approved native-dependency or TTS installer. | Only after the operator chooses installation; a system privilege prompt may follow. | Cancel installation and install prerequisites manually if desired. |

Active avatar-provider lookups use the **public-address filter** with a pinned DNS
result and revalidation at each redirect — see §6. Private/LAN, loopback, link-local,
and other blocked destinations are refused, including custom providers. This does
not claim that every unrelated HTTP client or integration uses the same helper.

Advisory translation is separate from the desktop's bundled UI translations.
The translation action can process multiple watched lists and tag descriptions,
not just the currently visible row. Separate entity-ID/name fields are not passed
as translation fields, but anything written inside messages, notes or descriptions
is part of the transmitted text. Remove sensitive content before requesting it.

Active Scarlet contains no Android/mobile companion, phone-pairing flow, mobile LAN
listener, FCM/OAuth mobile-delivery path, hosted mobile relay, or mobile device registry.
Native advisory notifications use desktop delivery; Discord logging remains
separate. Do not use old mobile setup or relay instructions for these builds.

## 2. What can control Scarlet remotely

The principal reviewed command interfaces are:

- **Your Discord bot.** Slash commands and interactive messages are the main remote
  surface. Access depends on Discord command settings, Scarlet's DPerms rules and
  any additional checks implemented for the action. Inspect stored member/role
  rules with `/scarlet-discord-permissions list` and select the target; authorized
  operators change them with `/scarlet-discord-permissions set`. Defaults, inherited
  roles and action-specific checks also affect effective access. `/config-info`
  is a configuration summary, not an authoritative view of effective interaction
  permissions. Protect the bot token and review permissions; possession of
  credentials can change this boundary.
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

Self-hosted does not mean that credentials never leave the machine: VRChat
authentication secrets are used for VRChat sign-in over HTTPS, and the Discord bot
token is used for Discord authentication. An explicitly configured translation
key is sent to its selected service; with the default MyMemory backend, that field
is treated as an optional email instead. Do not put a VRChat password or Discord
token in provider/translation fields. The reviewed avatar-provider requests send
identifiers as disclosed above, not those login secrets, and there is no active
Scarlet mobile relay receiving them. Local exports/backups can contain credential material;
protect those files and do not share them publicly. Encryption does not protect
against malware or another process that can use the same account and key material.

## 4. Update & announcement mechanism

**Update check.** Scarlet reads a small public file, `meta.json`, on the fork's
`main` branch. It contains release/build versions and an optional announcement.
The check fetches metadata, may query release/version lists, and can show a desktop
notice or send an update notice to the configured Discord operations channel.
It does **not** download or execute a replacement application JAR.

Startup checks are automatic. Scheduled update checks run approximately hourly;
announcement checks run approximately every ten minutes, with metadata caching.
`ui_alert_update`, `ui_alert_update_preview`, and `ui_alert_announcement` default
to true. These control notices or version selection, not whether metadata is
requested. Turning them off is not an offline mode.

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

An **external launcher that supports this update protocol** must read that file,
fetch the selected build and start it. The application does not guarantee that
your launcher implements this: a plain `java -jar` run exits without installing
the new build. You can instead download a release and launch it manually after
verifying its source and checksum.

**Restart is different.** Exit code `69` normally triggers Scarlet's own
`relaunchSelf()` path, starting the same JAR/classpath with the existing JVM and
application arguments. `-Dscarlet.restart.externalOnly=true` suppresses that
self-relaunch for an external supervisor; if self-relaunch fails, the external
launcher must handle the exit. Restart is not a build download. Separately, the
explicitly enabled companion-dependency recovery described above can fetch JARs
during startup; do not confuse it with update checking.

## 5. Process execution

Reviewed process integrations include:

- Launch VRChat through its registered Windows launcher, or Steam/URI handling
  on Linux — the "open instance" feature.
- Inspect local process identity using Windows native APIs or Java's process APIs
  to determine whether a VRChat client is running.
- Open a URL or a folder (`xdg-open` / the desktop browse API).
- Relaunch/restart itself (see §4).
- Run a configured local speech engine or initialize Windows SAPI speech.
- Run an operator-approved TTS/native package installation using a local shell,
  PTY, or privilege helper. Installation commands are not an unattended remote-control API.
- Request a VRChat client close when the corresponding automation is enabled;
  separately enabled Windows force-close recovery can terminate a verified client
  if normal closing fails. These paths have process/session checks, but operators
  should review the close and recovery settings before enabling them.

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

Use Java 21 for the recommended runtime and maintainer toolchain. The complete
current JAR requires Java 11 or newer; its Java 8 compilation banner is not a
promise of Java 8 runtime compatibility. See [BUILDING.md](BUILDING.md) for the
supported operating-system, architecture and native-library requirements.

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

1. **Use the matching frozen source.** Obtain the `scarlet-<version>-source.zip`
   attached to the same release as your JAR, and follow the `BUILDING.md` inside
   that archive. Audit and build that version. Do not assume repository `main` or
   GitHub's automatically generated source archives match a released JAR without
   verifying the source revision and release records.
2. **Compare against a checksum.** Each release should publish a checksum; verify the
   binary you downloaded matches.
3. **Diff the fork against upstream.** This is a fork of
   `SybylineNetwork/Scarlet`. Diffing against upstream shows exactly what this fork
   changed — the honest answer to "did the fork add something."
4. **Watch the traffic.** Run Scarlet behind a proxy or packet capture and confirm
   the destinations match §1.
5. **Read the sensitive files directly:** `util/EncryptedPrefs.java`,
   `util/HttpURLInputStream.java`, `ext/AvatarSearch.java`,
   `ext/ExtendedUserAgent.java`, `util/Translator.java`,
   `util/SecurityRegressionChecks.java`, and the update/restart and health-watchdog
   code in `Scarlet.java`.

### Health-monitoring limits

Health notices are best-effort diagnostics, not a guarantee that every failure is
detected or repaired. The current log-tailer watchdog warns after roughly five
minutes without a parsed log entry while VRChat is detected. It can mistake a
healthy quiet session for a stalled reader, including after VRChat starts when
Scarlet has already been idle. A subsequent entry can trigger a recovery message
without proving that every monitoring function has recovered. These false-alert
limitations remain unresolved in the reviewed builds.

The tailer warning does not repair or restart the reader. A separate session check
can attempt unattended VRChat re-login after confirmed authorization failure;
network errors can be inconclusive, and re-login is not guaranteed to succeed.
`/set-ops-alert-channel` enables a Discord destination for health notices, but
delivery also depends on a working Discord connection and channel permissions.
Use timestamps and logs to investigate a notice; do not treat silence or a
recovery notice as proof that everything is healthy.

## 10. Reporting a real vulnerability

Report suspected vulnerabilities privately to the maintainer where possible.
Include the affected version, relevant source location, impact and safe
reproduction steps. Never post passwords, tokens, cookies, private join links,
unredacted logs or exploitable private details in a public issue. For a public
documentation correction, describe the mismatch without exposing sensitive data.
