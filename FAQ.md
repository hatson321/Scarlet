# Scarlet Logger FAQ

[Home and setup guide](README.md) · [Settings](SETTINGS.md) · [Security and network behavior](SECURITY.md) · [Downloads](https://github.com/Hatson321/Scarlet/releases)

This FAQ describes the **0.6.1-a11 public preview** unless a different version is named. As of **November 1, 2026**, **0.6.0** is the published stable release; **0.6.1** is an unpublished candidate and **0.6.1-a12** is local release preparation. Preview features should not be assumed to exist in 0.6.0.

## Which file should I download?

Choose the ready-to-run `scarlet-<version>.jar` attached to the release you want:

- [0.6.0 stable](https://github.com/Hatson321/Scarlet/releases/tag/0.6.0)
- [0.6.1-a11 preview](https://github.com/Hatson321/Scarlet/releases/tag/0.6.1-a11)

The attached `scarlet-<version>-source.zip` is for source review or building, not the application installer. Repository `main` uses the **0.6.0 stable application baseline**, with refreshed documentation and the approved Help → Credits cleanup; it does not contain the a11 preview changes described in this FAQ. Those overlays mean **Code → Download ZIP** is not byte-for-byte identical to the original stable source archive. Prefer the release's frozen source attachment when reproducing that release. An unpublished draft is not a public update.

Windows and Linux use the same release JAR. No PowerPC JAR is attached to these public releases.

## Do I need to install Java?

The published JAR does not bundle Java. It needs **Java 11 or newer; Java 21 is recommended**. A compatible portable Java installation is also usable without a system-wide installation.

From the folder containing the JAR:

```text
java -jar scarlet-0.6.0.jar
```

For the public preview:

```text
java -jar scarlet-0.6.1-a11.jar
```

If installing Java did not help, reopen the terminal and run `java -version`. The `java` on your PATH or the JAR's double-click association may still point to an older runtime. Launching with the full path to the desired Java executable removes that ambiguity. JVM options go **before** `-jar`.

## Where are my settings and saved data?

Use **Data Folder** in Scarlet or `explore` in its CLI. Do not assume the active data folder is next to the JAR.

A separate directory can be selected with the `SCARLET_HOME` environment variable or case-sensitive JVM property:

```text
java "-DSCARLET_HOME=C:\Scarlet-Test-Data" -jar scarlet-0.6.1-a11.jar
```

A new empty directory gives a separate profile; it does not copy existing settings or watched lists. Keep the same choice on subsequent launches to use that profile. Do not point two running Scarlet processes at the same data folder.

## Will upgrading turn all my alert toggles off?

An upgrade is not intended to reset every existing setting. Saved values are loaded from the active data folder; settings absent from an older profile use the new build's defaults. A different data folder can therefore look like a reset.

Before upgrading, close Scarlet and keep an independent backup. Afterward, verify the displayed version, active data folder, and the relevant category switches. Renaming the JAR does not create or migrate a profile.

## Does Staff Mode need a group or Discord bot?

No. Staff Mode supports local player-list observations and alerts without requiring a group or Discord setup. It still needs the relevant local VRChat output logs and a working speech/output configuration. It does not grant group moderation privileges or create group audit events.

In the a11 preview, local watched-user/group/avatar and vote-to-kick alert eligibility is not restricted to an instance belonging to the configured group. Earlier stable behavior differs; use the exact release notes when comparing reports.

If a group-audit permission prompt appears, check which mode and profile were actually started. A group-enabled setup needs the signed-in account's real group permissions. Do not solve a missing group permission by blindly accepting prompts or granting unrelated administrator rights.

## Why do I hear an alarm but no spoken alerts?

An alarm tone and speech synthesis are separate paths. Check:

1. The global **TTS: On/Off** control.
2. A working installed voice and the system volume/output device.
3. **TTS: Use default system audio device** for desktop playback. When this is off, speech is routed toward Discord instead; configure a connected voice channel with appropriate bot permissions if that is intended.
4. The category's **Advisory** and **TTS** switches. Watched-entity and vote-to-kick callouts require both; other alert types do not all share that rule.
5. The watched entry's silent/message options and whether the event was actually observed.
6. Avatar-provider permission and a selected provider if the avatar cannot be resolved from available local/API evidence.

Use `tts test message` in Scarlet's CLI to check the configured speech route. A successful manual test does not prove that a particular watched event was detected. If only one category fails, report the category, exact settings, version, event time, and a redacted log excerpt.

## How does Linux speech work?

Scarlet uses supported local command-line speech engines: **Flite**, **Pico (`pico2wave`)**, **eSpeak NG**, or **eSpeak**, when installed and discoverable. Merely installing a voice pack does not establish that synthesis and playback work.

Use **Settings → Text-to-Speech → Install Linux TTS voices** to review the package choices and installation command. Installation may need your system package manager's approval. On supported atomic Linux detection, the installer uses an existing writable Homebrew installation; it does not automatically layer OS packages or reboot the computer.

After installation, refresh/restart Scarlet as prompted, select an available voice, and test the intended output route. Linux desktop playback prefers available system audio players and can fall back to Java audio. Distribution, session, permissions, and audio-device setup still matter. See [Settings](SETTINGS.md) for the controls.

## Why is a watched avatar missing or unresolved?

The client log does not always provide a usable avatar ID. Scarlet may use available API/cache evidence and, if permitted, selected external providers. Private, ambiguous, or unindexed avatars may remain unresolved.

The a11 cache keeps successful provider metadata for up to **24 hours**, and empty results for **five minutes**, across restarts. It stores metadata rather than downloaded avatar images. **Avatar search providers → Manage → Clear saved avatar results** clears that saved lookup state, but does not bypass privacy restrictions or guarantee a new result.

Provider access requires owner consent. Once enabled, selected providers can be queried automatically, not only when you press Search. Requests include identifying application/group/account information described in [Security](SECURITY.md). Only enable providers you intend to use.

## Why are kick logs missing when vote-to-kick logs appear?

These are different events:

- **Instance Kick** is a native group audit event.
- **Vote-to-kick** is an initiation observed from a local client log and routed as the extended event `groupex.instance.vtk`.

Assigning both to the same Discord channel does not inherently cancel either one. Check the actual event type, account audit permission, channel access, and whether the native event has reached Scarlet. A vote-to-kick alert alone does not prove that anyone was kicked.

If another moderator's standalone kick is missing, supply the exact build and approximate kick time with a redacted Scarlet log. The visible player list or a configured channel alone cannot confirm that the server audit entry was available.

## Why do Discord messages or moderation threads arrive late?

There are multiple stages: VRChat makes an audit entry available, Scarlet polls/processes it, the Discord embed is submitted, and follow-up thread/details are prepared. Provider/profile work, Discord rate limits, permissions, and network failures can all affect timing.

A “preparing moderation audit” message means work has started, not that Discord acknowledged the final message. The a11 delivery queue can retain unfinished work across restarts, but it is not an instant-delivery guarantee.

Report whether the **main embed**, its **thread**, or only **extra details** were delayed. Include both the event time and the eventual message time. Do not assume the same cause for every delay.

## Why are the Discord warning, kick, or ban commands unavailable?

The built-in Discord moderation commands are optional. Enable them under **Settings → Discord**, then verify command availability and the configured Discord interaction permissions.

The requesting member needs the appropriate Discord permission: **Moderate Members** for warning, **Kick Members** for kicking, or **Ban Members** for banning. Kick/ban also depend on bot permissions and role hierarchy. A feature toggle does not grant missing Discord rights.

For Scarlet interaction overrides, use `/scarlet-discord-permissions list` for the relevant target/type. `/config-info` includes a legacy role section and is not a complete display of current overrides. Tagging authority and banning authority are separate; see the [command reference](README.md#discord-command-reference).

## Will Scarlet join an already-open group instance?

With **VRChat Client: Launch on Instance Create / Startup** enabled, a11 checks the configured group's active instances on startup and requests a safe launch into the newest eligible one.

Eligibility, access, age verification, current lifecycle state, and safe client tracking still apply. “Newest” does not mean a hard-coded preferred instance, nor does it promise that Scarlet will take over an already-running client or switch into every second instance opened later.

If the wrong instance is selected, collect the exact version, relevant launch settings, and redacted launch diagnostics. Do not share full private join links or access keys.

## Why has VRChat not closed when the player list is empty?

In a11, enable **VRChat Client: Exit when instance is closed and empty** and save the setting. It takes effect without restarting Scarlet. This is separate from auto-launching and from the policy that closes an idle server instance.

Client exit requires all of the following:

- A matching group-instance closure audit for the current session.
- A healthy, caught-up local log roster containing the authenticated client account and no other players.
- A full **30-second healthy grace period**.
- Fresh session, roster, and process checks identifying the sole eligible same-OS-user VRChat client.

The a11 client-exit policy does not wait for separate API player-count confirmation. An instance that is empty but still open does not qualify. Missing self, unread/stale logs, another player, changed session, or ambiguous/multiple clients blocks the close. A historical “left” row in the UI is not itself sufficient authorization.

This policy can include a manually launched sole verified client. Its **separate default-off Windows force-close option** is more disruptive and only applies after a normal close attempt and fresh safety checks. It is not a blanket kill-by-name command. The idle **server-instance** close feature retains separate API occupancy checks.

## What happens if automatic VRChat startup stalls?

The a11 optional **Recover stalled auto-launch** feature watches verified Scarlet-owned automatic launches. It does not recover arbitrary manually started clients or later gameplay freezes.

Recovery uses bounded attempts and guarded quiet-log or repeated connection-failure evidence. It first attempts normal close, verifies exit, rechecks the target, and obtains fresh join information. A separate default-off Windows force-close option can apply to this startup-recovery path. Scarlet does not deliberately open a second client while the tracked one remains alive.

A join/access failure may come from instance state or account eligibility, not a frozen executable. Do not enable force termination as a substitute for investigating those conditions.

## Does “Created pre-import migration backup” mean my import finished?

No. It means Scarlet created the **pre-import safety backup**. Import may still be running.

In the preview, follow the console's remaining progress and wait for the final success/failure result. Existing-session export/import stops and saves Scarlet before a separate offline tool handles the transfer. Logging is stopped until Scarlet runs again.

Use a Scarlet migration bundle, not an arbitrary ZIP or individual backup file. Keep an independent backup until the restored data and sign-in are verified. Private staging may refuse a filesystem that cannot enforce suitable permissions. A failed or cancelled transfer should not be treated as a successful restore.

## Can I upload an export ZIP to a support issue?

Do not post migration bundles, credential files, tokens, or PINs in public issues. Exports can contain settings, history, watched lists, and encrypted credentials, and remain sensitive even when protected by a PIN.

Prefer a narrowly scoped redacted Scarlet log excerpt, exact version, relevant settings, and event times. Remove private join links/access keys and unnecessary personal information. Use **Data Folder** to find the right profile; do not upload the entire folder by default.

## Scarlet has neon or incorrect colors on Windows. What can I try?

In the a11 preview, try **Windows rendering compatibility (restart required)** under Appearance, **Save Settings**, then fully close and reopen Scarlet. It disables Java's Direct3D rendering for Scarlet.

For an older build or a one-launch check:

```text
java -Dsun.java2d.d3d=false -jar scarlet-0.6.0.jar
```

Use the actual JAR filename if different. An explicit JVM rendering option takes precedence over the saved preference. If the workaround only helps when launched from the terminal, a double-click launch may still use a different Java runtime or lack the option. This is a rendering workaround, not a diagnosis of a GPU or Windows failure.

## Is Training Mode safe for all commands?

No. Training Mode is **not a global dry-run switch**.

Use **Edit → Simulate event (training)** or `simulate` to create marked synthetic events and targets. Synthetic Discord events route only to the training channel, and the simulator's synthetic moderation targets use simulated results after permission checks.

An ordinary command targeting a real user or a real instance can still perform a real action while Training is on. Permission checks may still need actual group/account information. The training player list and marked records do not change that boundary.

## Why are some menus or Discord replies still in English?

Translation coverage varies. Russian has broad coverage, but English fallback and hard-coded English text remain; not every Discord reply is localized. Restart after changing the UI language when prompted.

Community `messages_<lang>.properties` files belong in the active data folder's `lang/` directory. Use the generated English template and `langlint` to check keys and placeholders. The separate [historical translation-string inventory](TRANSLATION.md) is not a current setup guide, and its line numbers may be outdated.

The separate `translate-advisories` operation sends selected prose to MyMemory or a configured LibreTranslate service. It is not purely local UI translation. `restore-advisories` can restore saved originals for watched groups, users, avatars, and moderation tags where originals exist.

## Does Scarlet have phone pairing or a mobile companion?

Not in the current release line. There is no Android companion, QR pairing, or mobile alert-delivery setup. Use supported desktop notification controls or configured Discord destinations.

## What does a health or recovery notice prove?

It describes a particular observation or recovery attempt, not overall application health. Quiet output logs can still produce misleading log-stall notices, and starting VRChat after Scarlet has been idle can affect those notices.

**Help → Scarlet: Diagnostics** displays tracked connectivity, rate-limit, and provider information without an extra API probe. The `vrchatapi-test` CLI command is different: it contacts the live API.

Saved sessions can still expire or need 2FA, and crash-safe saves/backups cannot guarantee zero data loss. Audit catch-up is bounded by the configured history window. Include the actual behavior and timestamps alongside a warning message when asking for help.

## Where can I ask for help?

Use the [Scarlet Discord community](https://discord.gg/CP3AyhypBF), the [repository's issues](https://github.com/Hatson321/Scarlet/issues), or the [VRChat Group](https://vrchat.com/home/group/grp_f12667c7-df5f-454f-9a34-5ed8c33112a1).

Start with the exact build, OS/Java version, relevant feature settings, reproduction steps, and event time. State what was expected and what actually happened. Do not post passwords, session tokens, private instance links, migration bundles, or unrelated personal information.
