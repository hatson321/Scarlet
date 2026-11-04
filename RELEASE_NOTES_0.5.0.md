# Scarlet Logger 0.5.0

Status: prepared locally; not yet published. This version continues the existing Scarlet release sequence and is based on the 0.4.20 mainline. Changing the local version does not publish a GitHub release, upload binaries, or notify existing installations.

## Cumulative maintenance changes

- Removed the optional private mobile relay and the entire Android/mobile companion feature, including desktop pairing, listeners, push delivery, configuration, and mobile build targets. The relay presented a confidentiality concern for selected alerts; the inspected materials did not establish an arbitrary-PC remote-control mechanism. Removing the feature does not shut down external infrastructure or alter old installed builds.
- Updated active maintainer and support identity. Original license information and third-party notices remain preserved.
- Prepared Windows/Linux build and package verification, including native-library and Java runtime-floor checks. Experimental PowerPC support remains experimental.
- Added the local Feature Report UI action, which lists only feature names and Enabled/Disabled states. It excludes channel assignments and other private configuration values and does not automatically upload the report.
- Restored the configured-template VRChat autofill form link alongside the copy/paste report and simple notes/tags form link. Long text and URLs are preserved in private attachments. A moderator must review and manually submit the report; external sign-in and form requirements still apply.
- Added migration safeguards and regression coverage. These safeguards do not make all migration operations transactional.

## Version coordination

The Maven build, runtime fallback, local latest-release/latest-build metadata, and launcher version marker use `0.5.0`. Main, debug, and experimental PowerPC packages derive their filenames and manifest versions from the Maven version. Historical release notes, dated announcements, and dependency versions retain their original numbers.

The existing version comparator orders `0.5.0` after `0.4.20`. Local metadata is prepared for this version but has not been published to the update endpoint. Publish matching artifacts before advertising the version remotely.

## Known limitations and release review

The earlier audit identified unresolved backup-write concurrency, calendar creation/automatic-close/recurrence, migration transaction, cache containment, text encoding, credential-transfer, and dependency/shading issues. This version-number change does not fix them. Consult the detailed public maintenance report before rollout; passing tests are not a guarantee that every feature or environment is defect-free.

The complete external launcher/downloader was not part of the acquired source audit. Live signed-in VRChat/Discord reporting, every Linux distribution, and PowerPC hardware operation require separate validation. No public release, signing rollout, or universal security certification is claimed.
