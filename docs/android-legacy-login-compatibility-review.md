# Legacy Android login crash and compatibility review

## Incident and confirmed root cause

The supplied `all_log.txt` contains one application-fatal exception at lines
4484–4489: `ClassCastException: android.content.res.MiuiTypedArray cannot be cast
to java.lang.AutoCloseable`, from `ItshoverNavigationIconView` construction.
The login-success callback restarts the home fragment (log lines 4536–4544);
Compose's navigation `AndroidView` then constructs that icon. Authentication has
already succeeded when the process crashes.

`TypedArray` implements `AutoCloseable` only from API 31. Calling Kotlin's generic
`.use` on a platform `TypedArray` compiled against a recent SDK inserts a cast
which cannot succeed on API 26–30. Desugaring does not add interfaces to the
installed Android framework. MIUI appears in the supplied exception, but the
incompatibility also applies to other older Android implementations.

The log does not establish the precise device model, OS release, or app
versionCode. In particular, this exception explains Android 8–11 failures, not
all reported failures on Android versions below 13. Android 12/12L need separate
coverage rather than being assigned the same cause without evidence.

## History check

Both the main and security worktrees started at `d372cb83` and contained the
same offending constructor. The icon code dates to `b5f2aacf`, when the app
required API 33. `7e95b0a9` restored minSdk 26 without changing that constructor.
The later compatibility fixes in `d8b81222` remain present, but did not cover this
call. Available refs/reflogs and unreachable Git objects contained no previous
fix for this exact exception. This does not prove that a never-committed or
already-pruned fix could not have existed elsewhere.

## Additional compatibility review

Source review alone did not identify another high-confidence defect, but the
subsequent API Lint run with `checkDependencies = true` found two additional
API-33 calls in the `novel-editor-core` library: `InputStream.readNBytes` in
`NovelImportParser`'s EPUB mimetype sniff and unknown-extension text sniff.
The initial run correctly failed with **2 NewApi errors and 63 warnings**;
these errors were not suppressed or baselined. The library's compatibility
calls and regression coverage are addressed below. This is not a claim that
every lower-API device behavior has been verified.

## Novel import API-33 calls (additional fix)

The shipped code28 Release APK is confirmed affected: `classes5.dex` contains
the direct `readNBytes` method reference inside `NovelImportParser`, and the
whole APK contains no corresponding synthetic backport class. On API 26–32,
importing a novel through the EPUB mimetype sniff or the unknown-extension
binary sniff would throw `NoSuchMethodError`. This is an in-app crash during
novel import, unrelated to the login crash, and it explains only import-path
failures — not login.

`NovelImportParser` now reads the sniffing prefixes through a new
`readPrefix(InputStream,int)` helper built on the always-available
`InputStream.read(byte[],int,int)` with explicit EOF, short-read, zero-progress
and failure handling. Sniffing semantics are unchanged: at most 64 bytes for
the EPUB mimetype and 512 bytes for the binary scan, never consuming beyond
the prefix, and binary NUL rejection stays inside the 512-byte window.

New `NovelImportParserTest` covers plain text without a `.txt` extension, NUL
bytes at the start/middle/edge of the sniffed prefix, `.txt` UTF-16 bypass,
EPUB recognition with and without a standard mimetype entry, mimetype-without-
container rejection, and `readPrefix` short reads, limit trimming, empty
stream, zero limit, zero-progress streams and error propagation: **11/11
passed**. These are JVM tests; the actual API availability is proven by the
lint run below, not by the JVM.

- The previous camera/window/insets, API 26–27 image-decoding, legacy URL-encoding,
  biometric, and notification `IntentCompat` fixes remain present.
- Production Java streams use `collect(Collectors.toList())`, not the newer
  platform `Stream.toList()`. Kotlin collection extensions are not that API.
- Core-library desugaring remains enabled with `desugar_jdk_libs:2.0.3`; its
  support table includes the inspected collection `of`/`copyOf` methods.
  Inspection of the existing Debug DEX also showed synthetic backports for the
  inspected `String.stripLeading`/`stripTrailing`/`repeat` call sites.
- The liquid navigation's backdrop, combined backdrop and effect modifiers are
  guarded by `blurActive`. Page backdrop creation exits before effect creation
  below API 33. Cached dependency bytecode confirms the shader capability check
  is a simple SDK predicate without static shader creation. The inspected
  `InteractiveHighlight` shader creation is guarded too.
- The Miuix blur dependency still declares minSdk 33 and uses the pre-existing
  manifest override. Existing fallback code and JVM checks do not certify OEM
  ART class loading or GPU rendering; device acceptance remains outstanding.

## Fix and regression coverage

The release versionCode is incremented from 28 to 29, retaining versionName 3.0.0
and minSdk 26. No dependency or toolchain upgrade is included.

The constructor now releases the framework `TypedArray` explicitly with
`try/finally` and `recycle()`, eliminating the platform-interface cast while
preserving attribute parsing and stateful icon colors.

The new real-View Robolectric test covers API 26, 28, 30, 31, 32 and 33, with three
cases per SDK: default construction/color updates, all four icon types with
literal tint attributes, and selected/default colors from the real resource
ColorStateList. It does not mock the View or TypedArray.

Before the production fix, the exact same 18 cases produced **9 failures and
9 passes**: all API 26/28/30 cases threw the same `ClassCastException` at the
constructor, and all API 31/32/33 cases passed. The failed run and XML were saved
locally as `/tmp/itshover-before-fix-test.log` and
`/tmp/itshover-before-fix-test.xml` (temporary diagnostic evidence, not committed
private user logs).

After the fix, the identical test command passed **18/18**, with zero failures,
errors or skipped tests. The corrected run is saved locally as
`/tmp/itshover-after-fix-test.log` and `/tmp/itshover-after-fix-test.xml`.

```sh
./gradlew :mastodon:testDebugUnitTest \
  --tests org.joinmastodon.android.ItshoverNavigationIconViewCompatibilityTest \
  --max-workers=1 --no-parallel --no-daemon \
  -Dorg.gradle.jvmargs='-Xmx768m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

### Broader validation

- Full `:mastodon:testDebugUnitTest`: **515 passed**, zero failures/errors/skips.
- Full `:reader-core:testDebugUnitTest`: **32 passed**, zero failures/errors/skips.
- `:novel-editor-core:testDebugUnitTest` completed but has no test classes; it is
  not counted as additional coverage. The combined run finished successfully.
- Compiled Debug `ItshoverNavigationIconView` bytecode contains `TypedArray.recycle`
  on normal/exception paths and no `AutoCloseable` reference.
- Build/test commands use one worker, no parallel tasks, in-process Kotlin,
  Gradle heap 768 MiB, and the existing app test-worker 512 MiB setting on this
  memory-constrained host. Full test log: `/tmp/abdl-legacy-full-tests.log`.

API Lint and signed-APK results are recorded below after completion.

### API Lint with dependencies

The same dependency-aware API Lint (`NewApi`, `InlinedApi`, `ObsoleteSdkInt`,
`checkDependencies = true`, init script `/tmp/abdl-legacy-api-lint.init.gradle`)
was re-run after the import fix and completed with **0 errors and 63 warnings**
(60 `ObsoleteSdkInt`, 3 previously reviewed `InlinedApi`; no `NewApi` anywhere
including dependencies). The pre-fix run with the 2 `NewApi` errors is preserved
locally as `/tmp/abdl-legacy-api-lint-before-import-fix.xml` and
`/tmp/abdl-legacy-api-lint.log`. This narrow check is not a full-repository
clean Lint verdict.

### Release build

`:mastodon:assembleRelease` completed successfully. The delivery copy is
`mastodon/build/outputs/apk/release/ABDL-Space-3.0.0-code29.apk`
(versionCode 29, versionName 3.0.0, minSdk 26, targetSdk 35):

- SHA-256: `008fc3f25d913069a6cc864dedb34cd59c489c4204f31d230f4dd69f21f7af8c`.
- APK Signature Scheme v2 verification passed; signer certificate SHA-256
  `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475` unchanged.
- A DEX scan of the new APK confirms the `readNBytes` reference is gone while
  `NovelImportParser` remains.

The generic `mastodon-release.apk` name in the build output directory is a
build artifact, not a delivery contract; use the `ABDL-Space-3.0.0-code29.apk`
copy. The original workspace's root-level old `mastodon-release.apk` and the
uncommitted QQ icon are preserved unchanged.

## Device acceptance boundary

The current Linux host has a working build SDK at `/home/ZYongX/Android/Sdk`,
but no connected ADB devices or installed AVDs. The Android plugin's default
SDK points elsewhere and its desktop emulator path is not functional. No SDK
licenses were accepted, no system SDK was replaced, and no device data was
cleared to work around this.

Robolectric, static inspection, and APK compilation are not a substitute for
MIUI/ART rendering on physical devices. Password/QQ/OAuth login through to the
home screen, already-logged-in cold start, both navigation styles, novel
import on API 26–32, camera, notification, and app-lock flows still require
device acceptance on affected Android versions. No real account credentials
or authenticated requests from the supplied log are reused in tests.

The Android 12 boundary statement stands: this fix explains the supplied
Android 8–11 crash evidence; Android 12 behavior was not reproduced from the
supplied log and needs its own device evidence if failures persist there.
