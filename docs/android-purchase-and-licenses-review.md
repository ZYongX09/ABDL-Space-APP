# Android purchase instructions and third-party licenses review

## Changes

- Move the payment reminder above the server-owned purchase steps. Keep the card baby pink and use dark text in both light and dark themes; bold/highlight `私信`.
- Ask `爱发电付款成功后点击页面内的什么查询兑换码？` before opening checkout. The choices are `返回按钮`, `私信按钮`, `获取兑换码按钮`, and `赞助按钮`. Only `私信按钮` continues.
- Keep incorrect answers in the dialog with visible, accessibility-live feedback. Cancel/back never opens checkout. A correct click dismisses the dialog and opens checkout once.
- Separate foreground-page validity from the underlying view's window focus: a modal owns focus, so checking the underlying scroll view's focus on a correct answer previously prevented checkout.
- Bind quiz acceptance to the verified configuration/generation, current session, active page, read timer and valid official URL. Dismiss pending quizzes on pause/hide, recreation and revalidation.
- Remove the QQ SDK disclosure text block from the About screen only. Keep QQ login/binding and their consent resources unchanged.
- Update the third-party list from resolved release runtime dependencies and recorded source adaptations. Correct AppKit to Unlicense, LiteX to Apache-2.0 and MaterialKolor to MIT; separate differing LiteX/Compose/Room/SQLite versions and add missing runtime/source entries. The list remains curated, not a complete generated transitive inventory or replacement for full license texts.
- Increment versionCode from 27 to 28; versionName remains 3.0.0.

## Verification

Executed together:

```text
:mastodon:testDebugUnitTest
  --tests org.joinmastodon.android.sponsors.*
  --tests org.joinmastodon.android.ThirdPartyLicensesContractTest
  --tests org.joinmastodon.android.OpenSourceLicensesPageTest
:mastodon:assembleRelease
```

Result: BUILD SUCCESSFUL. 57 tests, 0 failures, 0 errors, 0 skipped.

New Robolectric interaction coverage on API 26 and 35 includes all three incorrect choices, single correct launch, cancellation/stale clicks, invalidated page/configuration, launch while the underlying page has no window focus, light/dark reminder text and spans, actual license rows, link intents and missing-browser feedback.

Release output metadata: applicationId `top.abdl_space.app`, versionCode 28, versionName 3.0.0, minSdk 26. APK Signature Scheme v2 verification passed with the existing certificate SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`.

APK SHA-256: `352fb031f4e16b2d29d6b05a8d148e3d25bad69b87ddf7fe265b2804b5587875`.

Existing Gradle/resource deprecation warnings and D8 Kotlin metadata rewrite warnings remain; the release build completed successfully. No dependency/toolchain upgrades were performed in this change.

## Boundaries

This was targeted regression coverage, not the full repository suite or full lint/security review. No device installation, device screenshot acceptance or real Afdian payment/redeem-code end-to-end test was performed. The original workspace's uncommitted QQ icon and root-level old `mastodon-release.apk` were preserved unchanged.
