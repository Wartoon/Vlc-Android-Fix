# VLC Dual Backup System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Internal Storage and SMB produce distinct complete backups, mutually restore selected components from either backup, reject unrelated VLC backups, and remove superseded legacy backup/restore UI; clean the same legacy UI from TV without adding dual behavior.

**Architecture:** Keep the existing complete-backup implementation in `PreferencesAdvanced.kt` and the existing transactional staging/rollback hardening, but make the archive contract explicitly dual-family/versioned and make restore file-first. Build-time transformation scripts remain the reproducible source of custom behavior because the repository builds from the upstream VLC tree plus those transformations. TV cleanup is a separate final task on `tv-v1` so the untested TV backup implementation is not coupled to the dual-format migration.

**Tech Stack:** Kotlin/AndroidX Preferences, Android file picker/SAF integration already present in VLC Android, ZIP streams, JSON manifest text/JSON APIs already available in the project, Python build transformation scripts, GitHub Actions/Gradle.

**Spec:** `docs/superpowers/specs/2026-10-04-vlc-dual-backup-system-design.md`

## Global Constraints

- Accepted backup family is exactly `vlc-dual`.
- Accepted source variants are exactly `internal` and `smb`, each mapped to the actual package ID used by that build variant.
- Official VLC and every package outside that two-package allowlist are rejected.
- New complete backups use format revision 2; format acceptance is explicit rather than substring-based.
- Restore order is file selection -> validation/component discovery -> checkbox selection -> transactional restore.
- Cross-restore never changes the installed target variant/package identity.
- Existing transactional database staging/rollback guarantees must remain intact.
- TV remains a single-app backup system and does not receive dual-family cross-restore logic.

## Review Focus

- A syntactically valid ZIP with a forged/mismatched `variant` and package must be rejected before live data changes.
- A ZIP containing path traversal entries must be rejected during staging.
- A valid backup missing one optional component must still open selection, with that component unavailable/not offered.
- A cross-restore must not import preferences that encode the source APK's variant identity over the target APK's identity.
- An interrupted/failed selected database restore must preserve or roll back to the prior live database.

---

### Task 1: Lock down the dual backup manifest and variant identity

**Files:**
- Modify: `buildsystem/harden-backup-restore.py`
- Modify: `.github/workflows/build-fixed-apk.yml`
- Generated/verified at build: `application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt`

**Interfaces:**
- Produces: format-2 manifest fields `format`, `appFamily`, `variant`, `package`, `vlc`, and `components`.
- Produces: one validation routine that returns the trusted source variant/components only after family/package/variant checks pass.
- Consumes later: Tasks 2-4 use that validated metadata instead of `context.packageName` equality.

- [ ] **Step 1: Add failing workflow regression assertions** for `format:2`, `appFamily:"vlc-dual"`, both accepted variant names, a fixed package-to-variant mapping, and absence of the old `package == context.packageName` compatibility rule.
- [ ] **Step 2: Run the transformation/guard portion locally or in CI and verify the new assertions fail** against the current format-1/same-package implementation.
- [ ] **Step 3: Extend `harden-backup-restore.py`** so generated backup code writes format 2, derives the source variant from the actual dual build package, records component presence, and validates `appFamily + package + variant` against the two real dual package IDs.
- [ ] **Step 4: Add validation cases** covering Internal->Internal, Internal->SMB, SMB->SMB, SMB->Internal, unrelated package, package/variant mismatch, wrong family, and unsupported format.
- [ ] **Step 5: Run `python3 buildsystem/harden-backup-restore.py` followed by the workflow regression script/`git diff --check`** and verify all manifest/allowlist guards pass.
- [ ] **Step 6: Commit** with a focused manifest/interoperability commit.

### Task 2: Create distinct Internal and SMB complete backups

**Files:**
- Modify: `buildsystem/harden-backup-restore.py`
- Modify: `application/resources/src/main/res/values/strings.xml` if new user-visible naming text is needed
- Modify: `application/resources/src/main/res/values-de/strings.xml` if new user-visible naming text is needed
- Modify: `.github/workflows/build-fixed-apk.yml`

**Interfaces:**
- Consumes: Task 1 source-variant resolver.
- Produces: `fullBackupFile()`/equivalent output naming that cannot collide between Internal and SMB.

- [ ] **Step 1: Add failing guards** asserting Internal and SMB resolve to distinct names containing `VLC Complete Backup - Internal Storage` and `VLC Complete Backup - SMB` respectively.
- [ ] **Step 2: Verify current code fails** because it still uses one `VLC-Full-Backup.zip` path.
- [ ] **Step 3: Implement variant-specific backup filename generation** while keeping the existing complete archive contents and safe write-permission flow.
- [ ] **Step 4: Verify both names coexist and the manifest source identity agrees with the filename variant.**
- [ ] **Step 5: Commit** the backup naming change.

### Task 3: Make restore file-first and component-aware

**Files:**
- Modify: `buildsystem/harden-backup-restore.py`
- Modify: `application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt` through the reproducible transformation
- Modify: `application/resources/src/main/res/values/strings.xml`
- Modify: `application/resources/src/main/res/values-de/strings.xml`
- Modify: `.github/workflows/build-fixed-apk.yml`

**Interfaces:**
- Consumes: Task 1 validated manifest/component metadata.
- Produces: a restore-file picker result path/URI that is staged and inspected before `AlertDialog.setMultiChoiceItems` is created.
- Produces: selected restore flags only for components proven present.

- [ ] **Step 1: Add failing guards/tests** proving `full_restore` launches backup selection before `showFullRestoreSelection`, and that selection labels are derived from validated archive contents rather than a fixed assumed complete archive.
- [ ] **Step 2: Verify the current implementation fails** because `showFullRestoreSelection()` reads a fixed `fullBackupFile()` before any file choice.
- [ ] **Step 3: Implement a dedicated complete-backup picker result flow** distinct from the legacy settings-import picker, preserving the selected backup URI/path through validation and checkbox confirmation.
- [ ] **Step 4: Stage the chosen ZIP safely, reject traversal entries, parse/validate the manifest, and discover available components before showing checkboxes.**
- [ ] **Step 5: Build the checkbox list from available components** while preserving the existing logical choices: settings, media library/playback data, app DB, artwork, subtitles, credentials. Keep credentials conservative/default-off if retained.
- [ ] **Step 6: Add cases for missing optional component, empty/corrupt required selected database data, no checkbox selected, malformed manifest, and unsafe ZIP path.**
- [ ] **Step 7: Verify file-first ordering and all validation guards, then commit.**

### Task 4: Preserve transactional selective cross-restore and target identity

**Files:**
- Modify: `buildsystem/harden-backup-restore.py`
- Modify: `.github/workflows/build-fixed-apk.yml`
- Generated/verified: `application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt`

**Interfaces:**
- Consumes: Task 3 selected staged backup plus selected component flags.
- Produces: restore operation that changes only selected portable data and returns success only after selected replacements complete.

- [ ] **Step 1: Add failing regression guards** for unselected-component preservation and explicit filtering/preservation of target-variant identity during settings restore.
- [ ] **Step 2: Verify current settings restore has no target-identity protection** and therefore fails the new guard.
- [ ] **Step 3: Implement selective restore from the already validated staging directory**; do not reopen a fixed filename or re-trust unvalidated metadata.
- [ ] **Step 4: Preserve target-specific variant/package configuration when importing settings** while allowing portable settings from either dual variant.
- [ ] **Step 5: Retain `replaceDirectoryContentsSafely` / `replaceDatabaseFileSafely` and extend safe replacement to any selected directory component whose current path still uses destructive delete-before-copy semantics.**
- [ ] **Step 6: Exercise rollback guards** for media DB and app DB failure and assert unselected app DB/media/settings remain unchanged.
- [ ] **Step 7: Run transformation checks and `git diff --check`, then commit.**

### Task 5: Remove superseded legacy backup/restore UI from the dual apps

**Files:**
- Modify: `application/vlc-android/res/xml/preferences_adv.xml` via a reproducible transformation if upstream checkout recreates it
- Modify: `buildsystem/harden-backup-restore.py` or a focused new build transformation script if separation is clearer
- Modify: `application/resources/src/main/res/values/strings.xml`
- Modify: `application/resources/src/main/res/values-de/strings.xml`
- Modify: `.github/workflows/build-fixed-apk.yml`

**Interfaces:**
- Consumes: Tasks 2-4 complete backup/restore path.
- Produces: one user-facing backup system; low-level helpers needed by it remain available.

- [ ] **Step 1: Add failing guards** requiring the custom `full_backup` and `full_restore` preferences while forbidding superseded standalone user actions such as `restore_media_db`, `restore_app_db`, `dump_app_db`, and legacy settings export/import entries where they duplicate the complete system.
- [ ] **Step 2: Inventory each legacy handler before deletion** and retain any helper still called by the complete-backup implementation.
- [ ] **Step 3: Remove obsolete preference entries, click handlers, picker branches, and strings that are no longer referenced.**
- [ ] **Step 4: Run resource/reference checks plus the dual Gradle build to catch removed-resource or dead-handler errors.**
- [ ] **Step 5: Commit** the dual legacy cleanup.

### Task 6: End-to-end dual build verification

**Files:**
- Modify: `.github/workflows/build-fixed-apk.yml` only if verification gaps remain

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: two installable APK artifacts and CI evidence for the backup contract.

- [ ] **Step 1: Run all static regression guards and `git diff --check`.**
- [ ] **Step 2: Build `:application:app:assembleInternalStorage` and `:application:app:assembleSmb`.**
- [ ] **Step 3: Verify both APK artifacts exist and record SHA-256 values.**
- [ ] **Step 4: Inspect the GitHub Actions run through completion; if it fails, diagnose the exact failing step before changing code.**
- [ ] **Step 5: Commit any verification-only corrections separately.**

### Task 7: Remove legacy backup/restore UI from TV without changing its backup format

**Files:**
- Modify on branch `tv-v1`: TV build transformation/patch files that currently add or retain backup preferences
- Modify on branch `tv-v1`: `.github/workflows/build-fixed-apk.yml`
- Generated/verified on TV build: `application/vlc-android/res/xml/preferences_adv.xml`
- Generated/verified on TV build: `application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt`

**Interfaces:**
- Consumes: existing TV complete-backup implementation only.
- Produces: TV advanced settings expose the custom complete backup/restore flow and no superseded upstream standalone backup/restore actions.

- [ ] **Step 1: Inspect the current `tv-v1` transformations and generated advanced-preference code** to identify exactly which legacy entries are redundant; do not copy the dual allowlist/format-2 behavior into TV.
- [ ] **Step 2: Add TV workflow guards** requiring the TV complete backup/restore entries and forbidding the redundant standalone legacy entries.
- [ ] **Step 3: Remove only the redundant TV UI/handlers/strings while preserving helpers used by TV complete backup and the existing transactional hardening.**
- [ ] **Step 4: Run the TV transformation verification and single TV Gradle build.**
- [ ] **Step 5: Inspect the TV Actions run through completion and commit any evidence-based correction separately.**
