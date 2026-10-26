# Scarlet 0.6.1 pre-release acceptance

The priority remediation changes require both repeatable offline tests and an
operator-supervised acceptance pass. A successful build is not a declaration that
every integration or platform has been tested. Do not use production communities
as test fixtures.

## Repeatable offline gates

- Bootstrap the checksum-pinned VRChat SDK and build the vendored libdave Java modules, then run a clean Maven verify with an
  isolated `SCARLET_HOME`. Run on Windows and Linux with the documented toolchains.
- Run `scripts/VerifyBetaTests.java` with the final shaded Scarlet JAR,
  `target/test-classes`, and the JUnit Platform/Jupiter test-only JARs on the
  classpath. Do not include `target/classes` or a second runtime dependency copy.
  Set `-Djava.awt.headless=true` and `-DSCARLET_HOME=<isolated test directory>`.
  Also set `-Djava.util.prefs.PreferencesFactory=net.sybyline.scarlet.TestPreferencesFactory`
  to keep Java preferences in memory. Pass JVM properties explicitly, not through
  `JAVA_TOOL_OPTIONS`, whose banner interferes with subprocess protocol tests.
  This runner verifies that Scarlet classes actually came from a packaged JAR.
- Require all persistence, import, credential recovery, permissions, privacy,
  delivery retry, schedule, media-limit, native-lifecycle, installer-deadline,
  dependency and documentation regressions to pass without skipped tests.
- Inspect every generated desktop variant, its version and platform payloads.
  On Linux run the toolchain and ELF requirement checks. Static AArch64 payload
  checks do not substitute for execution on AArch64 hardware.
- Compare embedded dependency notices against the resolved upstream JARs byte
  for byte. Verify Scarlet's own LICENSE against its pre-request checksum.
  Review a current advisory inventory; no advisory match is not proof that a
  package has no unknown vulnerabilities.
- Record the exact final artifact SHA-256, OS, architecture, Java version, test
  counts and any warnings in the accompanying change report.

## Supervised release gates (not satisfied by offline tests)

| Area | Required acceptance evidence |
| --- | --- |
| Windows desktop | Start, first-run choices, close, restart and upgrade with synthetic data; cancellation must leave existing data intact. |
| Linux desktop | Repeat on the supported x86-64 distributions and intended display servers; test portable paths containing spaces and non-ASCII text. |
| Additional architectures | Execute on each advertised architecture; otherwise explicitly exclude that architecture or the untested feature from beta. |
| Discord privacy | In a disposable guild, verify normal, secret-staff and auxiliary destinations, public versus private command replies, history and message edits. Use synthetic identities only. |
| Discord moderation | With disposable accounts, confirm requester and bot permission/hierarchy checks, revoked permissions during confirmation and non-owner settings controls. |
| A12 queued actions and dialogs | Revoke a requester's role before a queued unban executes and while a settings/ticket/schedule dialog is open. Confirm no mutation, reject expired or cross-guild sessions, and reject legacy unbans without requester/group context. |
| Delivery | Disconnect/reconnect and restart between sends. Confirm pending events retry and acknowledged destinations are not intentionally resent. A crash after remote acceptance but before local acknowledgment can still produce a duplicate. |
| VRChat actions | In an explicitly authorized test group, verify successful, rejected and indeterminate ban/unban responses and timed-unban recovery; never act on real members as probes. |
| Scheduling | Verify due-time creation, canceled schedules after restart, and recovery from an uncertain remote result. Attempt markers require manual reconciliation instead of blindly creating a duplicate. |
| Backup and import | Close every Scarlet version. Import into a disposable data directory, test data-only mode, invalid PIN and failed writes; verify rollback and restart. Retain independent backups. Older builds cannot honor the new process leases. |
| A12 private staging and recovery | Use synthetic data on Windows ACL and POSIX filesystems. Verify other ordinary users cannot read plaintext staging, unsupported protections fail closed, corrupted records remain byte-identical after a rejected edit, and failed imports preserve recoverable originals. |
| A12 idle instance closing | In an authorized disposable instance, verify bot-only inactivity permits a soft close, while stale logs, missing self, another group, occupancy, account/setting changes and an arriving player during a lookup prevent closing or reset the full grace period. |
| Voice and TTS | Test real encrypted voice send/receive and speech on every enabled Windows/Linux voice path, including repeated connect/close and missing native dependencies. |
| Installer | With operator consent in a disposable environment, test cancel button/window close, privilege refusal, package-manager contention, timeout and successful completion. Fake-process tests do not prove privileged child termination on every OS. |
| Optional providers | Confirm disabled providers produce no request; enabled providers respect selected fields and blocked destinations. Use disposable public test data. |

Until these supervised gates are recorded for a feature/platform, label it
unverified and exclude it from beta or keep the release candidate private. No
live credentials, external moderation actions, voice sessions or privileged
installer executions are required by the offline suite.

## Recovery and compatibility notes

For the 0.6.1-a9 candidate, also record supervised checks of Staff Mode watched
alerts and vote-to-kick speech, newest-instance selection with multiple open
instances, persisted Windows rendering compatibility and the optional startup
recovery/force-close paths. Offline tests do not establish live acceptance.

Closed/empty client exit now uses a matching close audit and the healthy local
log roster, not independent server occupancy confirmation. Test unknown self,
unread logs, another player, changed sessions and multiple local clients as vetoes.
The exit opt-in can apply to a sole verified manually launched client; startup
recovery still requires Scarlet launch ownership. Force close is a separate
Windows opt-in, after normal exit is attempted. See README for the current policy;
historical test-build changelog entries describe earlier policies.

Imports are staged and validated before application, with verified rollback for
ordinary failures and an independent pre-import backup. Replacing multiple files
cannot provide one atomic transaction across a power failure; restore from that
backup if interrupted. Local filesystems must support atomic replacement; a
filesystem that does not support it fails safely rather than deleting good data.

Credential stores now include accidental-corruption detection; that checksum is
not an anti-tampering signature. An ambiguous legacy primary/backup pair fails
closed instead of guessing which credentials to discard. Historical exports are
not secure-erased. Rotate credentials if actual exposure is confirmed; code
redaction does not invalidate a previously copied credential.

The import UI provides the offline command and restart requirement. Do not edit
configuration or run an older Scarlet version concurrently with an import. New
instances also coordinate shared Java Preferences across different data folders.
Close all other Scarlet versions before the first-run legacy-folder transfer as
well. Its destination writes are lease-protected, but the legacy source is not
locked; copying a folder that another process is changing is not a consistent
snapshot.
