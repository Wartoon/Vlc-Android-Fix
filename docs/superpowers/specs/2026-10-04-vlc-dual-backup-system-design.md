# VLC Dual Backup System Design

Date: 2026-10-04
Branch: `dual-db-restore`

## Goal

Replace the remaining upstream VLC backup/restore user flows in the custom dual apps with one coherent, versioned backup system. Internal Storage and SMB must create distinct complete backups, accept each other's backups, and allow selective restore by component. Backups from unrelated VLC packages are intentionally unsupported.

The TV app keeps its single-app backup model; its obsolete upstream backup/restore user flows are removed separately without adding dual-app interoperability.

## Dual-app trust model

The two custom apps form one closed backup family. A backup manifest identifies:

- backup format version;
- application family (`vlc-dual`);
- source variant (`internal` or `smb`);
- source Android package ID;
- VLC/app version metadata;
- components actually present in the archive.

Each dual APK contains a fixed mapping of the two accepted package IDs to their expected variants. Restore accepts a complete backup only when the format is supported, the family is `vlc-dual`, the package is on that allowlist, and the declared variant matches that package. The current same-package-only check must therefore be replaced, not simply removed.

The official VLC package and all other packages are rejected. The manifest is a compatibility/allowlist boundary, not a cryptographic authenticity mechanism. Archive structure and selected source data are validated before live data is replaced.

## Backup creation

Internal Storage and SMB always identify their own source variant in the manifest and use distinct user-visible filenames, for example:

- `VLC Complete Backup - Internal Storage - <timestamp>.zip`
- `VLC Complete Backup - SMB - <timestamp>.zip`

This permits both complete backups to coexist in the same directory without overwriting or obscuring each other.

New backups use a new format revision rather than silently changing format 1 semantics. The restore layer remains explicitly versioned so later custom releases can add migrations or support additional revisions deliberately.

## Restore UX and data flow

Restore remains checkbox-based, but the order changes:

1. User chooses Restore.
2. User selects a backup file.
3. VLC stages and validates the archive and manifest.
4. VLC determines which supported components are actually present.
5. The component-selection dialog is shown with checkboxes only for restorable components.
6. User selects any combination and confirms.
7. Only selected components are restored.

A backup made by SMB can therefore be opened in Internal Storage and used only for settings, only for media data, for multiple components, or for everything. The reverse direction works identically.

The target APK always retains its own application/variant identity. Cross-restore must never rewrite the target package identity or turn Internal Storage into SMB (or vice versa).

## Components

The new complete-backup manifest records components independently. At minimum the existing custom system's logical groups remain independently selectable:

- settings/preferences;
- application database/data represented by the existing custom app-database backup component;
- media-library database/data represented by the existing custom media backup component.

If a component is absent or fails validation, it is not offered as restorable. Selecting all available checkboxes is the complete restore path; no second hidden legacy full-restore path is maintained.

Variant-specific values that must remain local to the installed APK are protected during cross-restore. Portable user settings and data are restored; target identity/configuration required to preserve the Internal/SMB build distinction is not imported blindly.

## Transactional safety

Keep the transactional restore hardening already added to the project:

- extract to staging first;
- validate before touching live data;
- stage replacement databases/directories;
- retain the old live data until the replacement has been installed successfully;
- roll back on installation failure;
- reject missing/empty required database sources.

Cross-variant support must not weaken these guarantees.

## Legacy backup removal

For both dual APKs, remove obsolete upstream VLC backup/restore menu entries, dialogs, and code paths that have been superseded by the custom complete-backup system. Do not remove low-level helpers still used by the custom implementation merely because they originated upstream. The resulting UI should expose one backup/restore system rather than parallel old and new systems.

For the TV app, perform the same legacy cleanup against its branch while preserving its current custom complete-backup behavior. TV remains a single accepted application identity and does not gain Internal/SMB allowlisting or cross-restore behavior.

## Error handling

Restore fails before modification of live data for unsupported format/family/package/variant combinations, malformed manifests, unsafe archive entries, or missing selected components. Errors should distinguish incompatible backup from corrupt/incomplete backup where practical. A failed transactional install restores the prior live data and reports failure.

## Compatibility policy

Official VLC backups are deliberately not supported. Future upstream VLC features may be ported into the custom apps independently of this backup format. Future custom backup revisions are handled by explicit format-version logic rather than assuming all revisions are interchangeable.

## Verification

The build workflow must add regression guards for the new contract and both dual APKs must still build in the same workflow. Verification should cover at least:

- Internal backup accepted by Internal;
- Internal backup accepted by SMB;
- SMB backup accepted by SMB;
- SMB backup accepted by Internal;
- unrelated package rejected;
- family/variant/package mismatch rejected;
- unsupported format rejected;
- component list derived from archive contents;
- selective restore does not replace unselected components;
- target variant identity survives cross-restore;
- transactional restore/rollback guards remain present;
- obsolete upstream backup/restore UI paths are no longer exposed.

TV verification separately checks that its custom complete-backup path remains available and the obsolete upstream user-facing paths are gone.

## Scope split

Implementation should be performed in two controlled stages:

1. Dual branch (`dual-db-restore`): interoperability, new manifest contract, backup naming, backup-first checkbox restore flow, selective restore validation, legacy cleanup, and regression guards.
2. TV branch (`tv-v1`): legacy cleanup only, preserving the tested single-app custom backup/restore implementation.

This keeps the untested TV build independent from the larger dual-app format change and avoids importing unnecessary dual-app behavior into TV.
