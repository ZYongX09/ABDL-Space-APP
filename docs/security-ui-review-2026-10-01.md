# Security UI review — 2026-10-01

## Changes

- Security switches explicitly use neutral unchecked colors rather than Miuix's default secondary accent track. Their accessibility semantics now expose the committed On/Off state.
- Failure-limit, timeout and both setup/change PIN-length selectors use a shared secure-window Compose Dialog with scrollable single-choice rows. The old OverlayDialog presentation path is removed from these security flows. Its original presentation failure was not independently reproduced on a physical device.
- Shared PIN UI follows assets/pin.pen and its light/dark reference PNGs: rounded lock tile, welcome heading for unlock, actual PIN-length description, spaced filled/unfilled dots, lower keypad, fingerprint at bottom-left and backspace at bottom-right. Short/large-font layouts scroll and wide landscape uses two columns.
- No fake status bar or gesture indicator is drawn. No forgotten-PIN bypass or destructive reset action is added.
- Repository, encryption, attempt quota, lockout clock and authentication logic are unchanged.

## Verification

`testDebugUnitTest` completed with 388 tests, zero failures/errors/skips. This includes six Robolectric Compose interaction executions across API26 and API35: requested versus committed switch state, all three single-choice option sets, and digit/backspace/biometric click delivery. These are component interaction tests, not full end-to-end settings navigation or pixel acceptance tests.

No available AVD or USB device was reported by Android preflight. Actual rendered-page light/dark, short-screen, landscape, fontScale 2.0 and physical biometric/Keystore acceptance remains unexecuted. Existing API26–35 compatibility tests remain in the regression suite. The existing Miuix blur minSdk override risk is not resolved by this UI patch.

Original QQ image and old root APK hashes were verified unchanged before synchronization. Signing configuration, package version and signing key are not changed.
