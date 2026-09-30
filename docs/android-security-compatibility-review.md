# Android security and API compatibility review

Reviewed on 2026-09-30. The application continues to support Android 8.0 (API 26),
with targetSdk 35 and compileSdk 37. This review does not raise the minimum SDK.

## Correctness fixes

- A single process authentication tracker owns the app-lock session. Unknown cold-start
  state and unreadable security storage do not authorize protected entry points.
- Activity checks perform storage work off the main thread, invalidate obsolete results
  on pause/destroy/session change, and keep an opaque, accessibility-blocking shield until
  a successful verdict. The shield follows the application's light/dark theme.
- Lock success/cancellation clears launch ownership. A second background interval longer
  than 30 seconds can launch a new lock screen. Configuration recreation retains the
  ViewModel, while app/process restart does not restore authentication.
- Launcher, main navigation, share, document import, OAuth, verification links, and QQ
  callbacks defer their protected work until the owner Activity is resumed and authorized.
  The original Intent/URI grants remain with the original Activity.
- PIN input stays in a private ViewModel buffer; the public state exposes only the input
  count. Successful completion is state-driven and idempotent. The lockout display refreshes
  while foregrounded instead of requiring the user to leave and reopen the screen.
- Notification actions still require the lock screen. Nested notification Intents are read
  through IntentCompat; RemoteInput results are preserved for the deferred service action.

## Biometric compatibility

The production setup flow is now a single CryptoObject-based BiometricPrompt path rather
than an unavailable controller plus a separate UI implementation.

- Strong-biometric availability is checked before preparing a key or opening a prompt.
- API 26–29 keys use legacy per-operation authentication. API 30+ explicitly uses
  `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`.
- Setup prepares a user-authentication-bound AES/GCM key before the CryptoObject prompt;
  unlock only loads the existing key. Missing/permanently invalidated keys are not silently
  recreated by unlock.
- Authentication failure is non-terminal. Cancel, dispose, stop, and outdated request
  callbacks cannot complete a later request. After cancellation the fingerprint button
  can explicitly request a new prompt.
- Successful biometric authentication verifies the authorized cipher before granting access.
  The repository clears PIN failure state only after successful biometric completion.
- Setup cancellation and repository failure clean up the setup key. Disabling biometrics
  retains the actual repository state if the state write fails.
- PIN remains the fallback; system device credentials are not introduced as an alternative
  to the application PIN.

## Confirmed legacy API fixes

The initial Android Lint report identified unguarded calls above minSdk in application
code. These were corrected without suppressing NewApi:

| Area | Correction |
| --- | --- |
| Camera window and status-bar insets | WindowCompat, WindowInsetsControllerCompat, ViewCompat and WindowInsetsCompat |
| Camera photo/video rotation | WindowManager's legacy default-display API |
| NBW bind/register and SpaceBackgroundView insets | ViewCompat/WindowInsetsCompat instead of API 30 framework insets |
| NSFW image decoding | BitmapFactory/InputStream fallback on API 26–27; existing ImageDecoder path retained on API 28+ |
| Private/public novel URL encoding | UTF-8 string-name overload instead of the API 33 Charset overload |
| Security window configuration | Configure edge-to-edge before composing; retain API 29/31 guards |
| Small PIN screens | Do not force narrow portrait devices into an unusably narrow two-column layout |

## Miuix and graphics boundary

The cached Miuix 0.9.3 UI/preferences artifacts declare minSdk 23, but
`miuix-blur-android:0.9.3` declares minSdk 33. The existing manifest override remains;
removing it without replacing the dependency would make manifest merging fail.

API 26–32 use the non-runtime-graphics path. Blur backdrop creation and runtime effects
are guarded at the application boundary; liquid navigation does not create its tabs
blur backdrop when effects are unavailable. The background shader also returns to its
ordinary Box path before initializing the effect painter.

Robolectric tests check the support predicate, the library's negative capability response
below API 33, and loading of the non-effect backdrop types. This is not a guarantee that
all vendor ART/rendering implementations behave identically. The remaining manifest
minimum-SDK override is an explicit release risk requiring device smoke testing, not
proof of full lower-API support by the upstream blur library.

## Verification

- Full `:mastodon:testDebugUnitTest`: **382 passed; 0 failures, 0 errors, 0 skipped**.
- App gate/shield tests: API 26, 28, 30, 31, 33 and 35 (24 executions).
- Biometric PromptInfo/KeyGenParameterSpec/session/error contracts: API 26, 28, 29,
  30, 31, 33 and 35 (28 executions). These tests do not exercise a physical sensor or
  the device's real AndroidKeyStore provider.
- Graphics fallback/loading tests: API 26, 28, 29, 30, 31, 32, 33 and 35 (16 executions).
- Camera window/insets and background insets tests: API 26, 28, 30, 31 and 35
  (10 executions; no camera capture hardware is exercised).
- Android 8/8.1 image decoding: API 26 and 27 (2 executions).
- Private/public novel URL/path tests: 4 executions.
- AuthTracker and lock ViewModel regression tests include 29/30/31-second grace,
  repeated locks, forced authentication, corruption, clock rollback, duplicate success,
  PIN count/backspace and expiry refresh.
- `:mastodon:assembleDebug`, `:mastodon:compileReleaseKotlin`,
  `:mastodon:compileReleaseJavaWithJavac`, and `:mastodon:processReleaseManifest` pass.
  Release compilation is not a signed Release APK delivery.
- Test-only Android resources are enabled. A dedicated Robolectric manifest avoids
  auto-registering the bundled JPush SDK's legacy JVM receiver during unit tests.
  Production manifests and push components are not removed for that workaround.
- Multi-SDK Robolectric classes run in separate JVM forks with a 1 GiB heap and no
  parallel forks. This avoids retaining all SDK sandboxes in the former 512 MiB worker.
- An existing verification test now explicitly targets API 26 rather than Robolectric's
  default API 21, which cannot load this application's minSdk-26 resource APK.
- Final API-only Android Lint (`NewApi`, `InlinedApi`, `ObsoleteSdkInt`): **0 NewApi
  errors**, 59 ObsoleteSdkInt warnings and 3 InlinedApi warnings. The photo-picker
  constants are behind API 33 / Android R extension-version checks; the media-volume
  constant is only consumed by the API 29+ save path. These remaining constants were
  reviewed rather than suppressed. This narrow check is not a full-repository clean
  Lint verdict; the earlier unrestricted report contained unrelated existing findings.
- The initial combined full Lint/build command was stopped after prolonged source-type
  analysis. A separate Debug/Release build and the corrected API-only Lint invocation
  both completed successfully. D8 still emits the existing Kotlin metadata compatibility
  warning for the Kotlin 2.4.10 / AGP 8.9.1 toolchain; no toolchain upgrade is included.

## Unexecuted device acceptance

No ADB-connected Android device was available during this review. The following are
not claimed as passed:

- Actual API 26/28 ART cold-start and full Compose/Miuix rendering, theme, navigation-bar,
  short-screen and fontScale-2.0 UI smoke tests.
- Physical fingerprint/face enrollment, success, lockout, and enrollment-change key
  invalidation (especially OEM/MIUI/HyperOS behavior).
- End-to-end notification reply, QQ/OAuth callback, share URI and document import GUI flows.
- API 31 overlay protection and API 35 predictive-back/edge-to-edge device behavior.

The app lock is a UI/operation gate. It does not encrypt existing account/chat/novel
SQLite databases or protect against a compromised/rooted operating system.
