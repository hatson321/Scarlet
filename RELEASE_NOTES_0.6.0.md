# Scarlet Logger 0.6.0

A cumulative stable release for Windows and Linux, covering the maintainer-branch changes since 0.4.20 through 0.6.0. It includes the fixes developed through A13: saved-history moderation summaries, more reliable Discord audit delivery, safer saving and permission checks, and a protected VRChat launch/restart process. These cumulative fixes are not presented as newly introduced in 0.6.0.

> Version 0.6.0 is designated stable at the maintainers' request. The known issues and outstanding platform checks below still apply. These notes are prepared locally; the release and user announcement have not been published.

## Highlights

- Generate moderation summaries from the history already saved on the logger computer, with background processing and a count of unreadable files excluded from the result.
- Keep moderation-thread work out of the audit polling path, recover unfinished deliveries after restart, and slow API-backed audit requests when VRChat rate-limits them.
- Accept complete instance links and report scheduled, already-closed, rejected and uncertain close outcomes more clearly.
- Protect unreadable saved data from accidental replacement and recheck moderator authority when delayed actions or settings dialogs are used.
- Retain the launcher protections introduced in A13: restart only a VRChat client whose launch Scarlet can verify, request normal shutdown, confirm exit, and prevent overlapping launches.

## Release preparation in 0.6.0

- Use the stable `0.6.0` version consistently in the application, release metadata, filenames and launch instructions.
- Replace two authentication examples in `SETTINGS.md` with nonfunctional placeholders before creating the matching source archive.
- Prepare an updated GitHub-hosted in-app announcement for the stable release. Publishing the release and activating the notice remain separate, reviewed steps.
- Carry forward the cumulative fixes below; changing the release designation does not resolve the remaining known issues or substitute for verification.

## Cumulative changes and fixes

### Moderation summaries from saved history

- Manual `moderation-summary` and scheduled summaries now read Scarlet's structured audit history on the local computer. They do not scan Discord messages or diagnostic text logs, download VRChat audit history, or fetch staff profiles to generate the summary.
- Summary generation runs in a dedicated background worker. One summary runs at a time; extra requests receive a busy response, and shutdown cancels the worker.
- Results respect the current group, selected dates, redactions, confidential staff and training exclusions. Duplicate receipts count once; a related ban and kick remain separate moderation events. Unresolved attribution is not guessed.
- Unreadable records are skipped and add nothing to moderation totals. The result is labeled partial; the final summary states how many files could not be read and directs the operator to local logs. A readable duplicate cannot bypass a potentially newer, unreadable privacy record.
- Corrected the headline invitation count to use retained invitation events. Local join records must match the group.
- Summaries describe **saved local history**, not guaranteed complete VRChat history. Missing, deleted, never-collected and still-unprocessed events cannot be counted. Unsafe directories, privacy conflicts, cancellation and resource limits can still stop the summary without returning totals.

### Discord audit delivery and VRChat rate limits

- Added a durable local audit-delivery queue, so unfinished work can be retried after a failure or restart. An acknowledged main embed is not deliberately reposted just because its follow-up is unfinished.
- Moderation threads and their action controls are prepared in the background after the main embed is sent. Current privacy and destination checks still apply to recovery work.
- Kicks bundled into a ban thread wait durably for that thread. A normal dependency wait no longer consumes failure retries or raises a misleading delivery/channel-permission warning. Ready threads wake waiting receipts; periodic recovery covers missed notifications and restarts.
- Independent public snapshot lookups use bounded parallel work, deadlines and cancellation. Optional enrichment can be incomplete or skipped under load instead of creating unbounded work.
- API-backed audit-history GET requests share one pacer, allowing at most one request every two seconds. A rate-limit response starts at least a 30-second shared cooldown, including when `Retry-After` is zero or missing; longer server waits are honored, and repeated limits increase the cooldown.
- This policy does not automatically replay routine polling requests or moderation writes. The separate `actor-moderation-summary` and `outstanding-moderation` commands remain API-backed.
- Improved filtered-history fallback, bounded Discord embeds, delivery timing diagnostics and shutdown of background work. These changes do not guarantee instant delivery or eliminate authentication, network or service failures.

### Moderation permissions and timed bans

- Enforced requester authority and hierarchy for Discord kick/ban and VRChat ban actions, not only the bot account's permissions.
- Bound confirmation actions to the intended requester, action and target, with recoverable pending-action reservations.
- Recheck current requester authority before queued bulk unbans execute and before changes from an open settings dialog are applied. Expired dialogs and legacy queue entries without verifiable requester/group context are rejected.
- Keep timed bans tied to their original group and confirmed server ban identity. A replacement ban cannot be removed merely because an older timer expires.
- Legacy timers and uncertain ban history require manual review. Temporary verification failures postpone the check instead of treating missing evidence as permission to unban.

### Confidential information and snapshots

- Filter moderation snapshots to approved public profile/group fields when capturing and displaying them. Snapshot buttons and audit repair actions verify their source event and current access policy.
- Strengthened confidential-staff routing, private history, redaction handling and webhook credential protection. Missing or unreadable privacy/permission state cannot silently grant default access.
- Preserve concurrent changes to moderation details when updating redaction fields, instead of replacing unrelated saved values.
- Serialize confidential-staff persistence and retain failed state for recovery where applicable.
- New launch diagnostics omit full command arguments and secure join links. Close-instance diagnostics use bounded, filtered fields rather than dumping headers or raw server responses into Discord.

### Saving, backups, export and restore

- Added restore from a compatible Scarlet export or pre-import backup before first-time login, plus consent-based data-folder migration.
- Import/export now stop and save the running session before handing off to the offline tool. Data/preference locks and worker shutdown checks protect that handoff.
- Hardened coordinated saves, UTF-8 handling, encrypted-preference recovery, alternate-account credential removal and startup storage locking.
- Existing unreadable saved records are not treated as empty defaults and overwritten. This covers settings, Discord configuration and permission rules, schedules, watched lists and the regular staff list. The affected store refuses writes until valid loading or recovery succeeds.
- Failed settings saves report an error; pending in-memory settings can be retried after storage is repaired. A valid settings backup can still be used for recovery.
- Validate archive paths, sizes and import scope; stage imports before applying them; preserve pre-import backups and rollback paths. Credential and channel-route selections are respected.
- Export destinations must be new and verified. Optional cleanup and move-out deletion have containment checks; unrelated independently copied backups are not searched for or removed.
- Sensitive plaintext staging for encrypted backup/import requires enforceable private filesystem permissions. If those permissions cannot be established, the operation stops.

### Instance commands and automatic closing

- `vrchat-group close-instance` accepts complete official launch links, including bare `~ageGate` flags, encoded/reordered parameters, and full raw locations. Incomplete, malformed and wrong-group inputs are rejected before a close request. A world ID alone or short link is not sufficient.
- Fixed date-only account-join values in nested instance-user details that could make an accepted close request appear to fail. Unrelated event and closure timestamps remain strict.
- Distinguish immediate, scheduled, already-closed, rejected and uncertain outcomes. An unreadable/uncertain response advises checking the instance state rather than deliberately sending the close again.
- General HTTP 403 replies no longer assume that missing permissions are the proven cause. Discord receives a reference ID that can be matched to filtered local diagnostics, including the requested options and effective closing time when known.
- Idle-instance auto-close now requires a healthy current log session, the configured group, the bot alone in the observed roster, and a fresh server occupancy check. Uncertainty restarts the grace period. It requests a normal soft close, not a hard close that ejects occupants.
- Added a separate, disabled-by-default option to exit Scarlet's managed VRChat client after its exact instance is already closed and empty of other players. It uses a grace period and fresh checks; Scarlet stays running, and this option does not itself close the server instance.

### VRChat launcher safety (included from A13)

- Removed process-name-wide termination and the force-kill fallback. A restart requires a uniquely verified managed client, using its process identity, start time, operating-system user and observed launch ancestry.
- Request a normal close, wait for confirmed exit and a short quiet interval, then launch a replacement. A failed close, changed identity or uncertain process inventory prevents the restart.
- Allow only one launch lifecycle at a time. Startup observation is bounded; a known unresolved launcher or observed child continues to block another launch until its exit is verified.
- Run automatic launch waits separately from moderation logging. Extra requests are skipped rather than queued for a surprise later launch; shutdown disables new launches and cancels active waits.
- The instance-creation wizard does not restart an already-running client. It reports a launch refusal separately from successful instance creation and offers an explicit self-invite.
- Manually started clients, clients already running when Scarlet starts, and Steam/URI handoffs without verifiable ancestry remain unowned. Use an invite or close those clients yourself. These safeguards do not establish EAC readiness or confirm a fix for a particular EAC error.

### Discord configuration and live observations

- Fixed audit selections incorrectly falling back to Instance Close. Validate event destinations, modal values, numeric/time inputs and paginated selections before saving; improve confirmations for failed changes.
- Persist webhook, permission and calendar-setting edits correctly, with settings interactions tied to their authorized owner.
- Improved the independent Live Player List route, startup/restart catch-up, observation-end handling, timestamps and update retries. Confidential members are filtered before visible roster counts are calculated.
- Support current and older prop-spawn log formats, retaining observed alerts when optional metadata is unavailable.
- Corrected local-account authentication recognition and accidental replay of a previous log entry. Improved scheduled-instance due-time/persistence and shutdown/IPC handoff behavior.
- Save calendar-creation intent before sending the request and persist the returned event ID immediately. Uncertain results pause automatic scheduling instead of blindly submitting the same event again.

### Reports, external providers and desktop support

- Restored the official prefilled VRChat report-form link alongside the copyable report template. Preserve long report text and links in attachments; a moderator still reviews and submits the report manually.
- Added a voluntary local Feature Report showing enabled/disabled feature states for group owners to share. It does not upload automatically or include credentials, account IDs or usage history.
- Require explicit consent for external avatar-provider queries that share Scarlet's version, the configured group ID and primary signed-in account ID. Declining leaves those lookups off. Provider selection and network destinations are constrained.
- Removed the Android build, mobile companion and relay over confidentiality concerns. Windows/Linux desktop notifications remain available.
- Improved first-start Linux dialogs, supported Fedora Atomic/Bazzite TTS setup routes, bounded speech/helper processes, GIF allocation limits and native voice-buffer cleanup. Improved handling of spaces and Unicode in paths.
- Updated maintainer/support metadata and direct official calendar links. Numbered build suffixes now compare numerically, so A10 sorts after A9.
- Updated the VRChat Java SDK to 1.23.0 and selected dependencies; corrected changed request arguments, response wrappers, audit representations, profile caches and instance/calendar defaults.
- Added documented build entrypoints, a checksum-pinned SDK bootstrap, packaged regression checks and static native/package validation. The source ZIP is prepared from the frozen 0.6.0 source, including the documentation placeholders. Building it requires JDK 21 and Maven 3.9+; see `BUILDING.md`. A first build still needs dependency downloads.

## Before updating

- Keep an independent backup of Scarlet data and secure settings. Archives can contain credentials and moderation data; store them privately.
- Close the old Scarlet process before starting 0.6.0. A different JAR filename does not create an isolated data folder. If an older build does not recognize 0.6.0 as newer, install it manually.
- The JAR requires Java 11 or newer; Java 21 is recommended for this build. Java is **not bundled** in these assets. Confirm the Java version in the terminal you use to launch Scarlet.
- Reassign event channels previously saved incorrectly by older builds. Scarlet cannot infer the intended destination from an old incorrect Instance Close assignment.
- Export stops logging until Scarlet is restarted. Choose an ordinary filesystem folder, not a virtual location such as “This PC.” Use storage that supports private permissions for sensitive backup/import staging.
- Review legacy or uncertain timed bans manually. Do not assume a saved timer proves that the currently active ban should be removed.
- Supported Linux voice/build validation uses glibc 2.38 or newer, with compatible C++/GCC runtimes (`GLIBCXX_3.4.32`, `CXXABI_1.3.9`, `GCC_3.0`). A static package check is not a live voice test. PowerPC remains experimental.

Run the desktop build with:

```text
java -jar scarlet-0.6.0.jar
```

## Known issues and limits

- Evidence-capture mappings for F13–F24 still need correction; the default F9 mapping is unaffected. Avoid using the higher function-key mappings until fixed.
- Temporary login errors can still lead to misleading credential-reset prompts, and an alternate-account reset can target the main account. Do not accept that reset prompt as a remedy for a temporary service failure. Saved-authenticator recovery can also ask for a manual code unnecessarily.
- Watched-group URL imports can block the UI. Moving that download/parse/save work to a bounded background worker remains a follow-up.
- Cleanup failure after an encrypted import commits can still make the operation appear to have failed. Check the resulting configuration and logs before repeating an import.
- The launcher still passes an account ID to the `--profile` option, whose numeric-selector mismatch remains unresolved. A13's process-safety changes do not correct that argument.
- Local summaries cannot reconstruct missing history and retain scan limits, including a five-minute deadline. API pacing cannot guarantee avoidance of every service limit. A normal instance soft close blocks new joins but does not eject occupants already inside.
- VRChat does not make final timed-unban verification and the unban request atomic. Likewise, a player can join between the last occupancy check and an instance-close request.
- A crash after Discord accepts a message but before Scarlet saves its receipt can still cause a duplicate on recovery.
- Existing raw snapshot files, old backups and previously shared messages are not scrubbed by these changes. Keep them private.

## Verification and release review

Fresh 0.6.0 verification on October 18, 2026 used synthetic fixtures on Windows with JDK 21. The offline build used the existing dependency cache; this is not an empty-cache build result.

| Check | Result |
| --- | --- |
| Maven clean build and regression suite | Build succeeded; 1,416 passed, 0 failed, 6 skipped |
| Tests against the actual packaged JAR | 1,416 successful, 0 failed, 6 environment-aborted |
| Packaged application classes | 772 matched the compiled output and resolved without initialization |
| Static package checks | Main, debug and experimental PowerPC archives passed the structural checks |

The six skipped/aborted cases require filesystem-link support unavailable in the test environment. They are not passes; the strict packaged-test runner returned a non-success status because it rejects aborted tests.

Supervised Windows/Linux testing of first-time setup, login, saving/restoring, Discord delivery/voice and real VRChat launches remains necessary. A clean build with an empty dependency cache, the skipped filesystem cases, and outstanding optional-package compatibility diagnostics also remain review checks.

AI assists development and API updates. Maintainer/admin code review and live beta acceptance remain part of the release process. Automated results are not a guarantee against every fault.

## Release assets

Release filenames are listed below. The JAR has been built and verified locally. Check the GitHub draft's attached assets before publishing; listing a filename here does not attach or publish a file.

- `scarlet-0.6.0.jar` — the shared Windows/Linux desktop JAR.
- `scarlet-0.6.0-source.zip` — the matching frozen source archive, including build instructions and regression tests.
- `SHA256SUMS.txt` — SHA-256 checksums for both files.
