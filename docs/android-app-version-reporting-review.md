# Android app version reporting review

Reviewed 2026-10-03, starting from clean Android worktree HEAD `e0a61230`.
All Android changes and build outputs are confined to
`/home/ZYongX/projects/ABDL-Space-APP-security-worktree`.

## Wire contract and release identity

`MastodonAPIController.submitRequest` now sets mandatory native identity **after**
optional `req.headers`:

- `User-Agent: MastodonAndroid/<BuildConfig.VERSION_NAME>` retains the existing
  anchored native-client detection scheme. Release is `MastodonAndroid/3.0.0`;
  Debug remains `MastodonAndroid/3.0.0-debug`.
- `X-App-Version-Code: <BuildConfig.VERSION_CODE>` is `30` for this release.
  OkHttp `header` replaces existing values case-insensitively, so conflicting
  caller headers cannot change the build identity or add duplicate identity values.

The controller-wide contract covers every current timeline request constructor
(home, all, public/local/remote/federated, geo, popular, NBW, bubble, tag, and both
list overloads), including unauthenticated requests and requests using session or
request bearer tokens. It also applies to other requests submitted through this
controller; it is not a guarantee for unrelated direct OkHttp clients, third-party
SDKs, image fetches, websocket connections, or external upload transports.

Release `versionCode` advances from 29 to 30. `versionName` stays `3.0.0`, minSdk
stays 26, targetSdk stays 35, and compileSdk stays 37. No toolchain, dependency,
QQ configuration/icon, or transport security policy is changed. Authentication
precedence, optional nonidentity headers, cache policy, redirect policy, sensitive
client selection, certificate trust, and response validation retain their existing
behavior. No installation ID, device ID, IP field, or other tracking identifier is
added. Optional `X-App-Version-Name` is deliberately not added.

## Metrics boundary

The transmitted value describes the installed app **build**, not an installation,
unique device, person, or download. Requests can repeat, multiple devices may share
an authenticated account, and one installation can use multiple accounts. The
header is client-supplied metadata, not authentication or attestation, and a
modified/nonofficial client can spoof it. Missing or malformed values from legacy
clients cannot reliably identify a version. Server metrics based on authenticated
timeline observations can describe observed account/build activity only; they must
not be represented as exact installation, device, download, or complete active-user
counts. Android adds no local reporting database or background tracking job here.

## Synthetic update response compatibility

At this Android HEAD, `GetAllTimeline` extends
`MastodonAPIRequest<List<Status>>`. Despite its custom endpoint, its JSON response
is a **bare status array**, not `{statuses: ..., next_max_id: ...}` or another
object envelope. The next cursor comes from HTTP `Link: <...max_id=...>; rel="next"`.
`HomeTimelineFragment` uses that cursor for its FOLLOWING timeline; an update-only
response without `Link` is terminal on this path.

The backend-shaped notice fixture is based on `appUpdateNotice` and the
`toAccount`/`toStatus` converter defaults reviewed during implementation:

- fixed string status ID `app-update-required`, reserved numeric account ID `-1`,
  username/acct `app-update`, bot account, valid UTC created time and public visibility;
- URI `https://abdl-space.top/app#update-required`, URL/download link
  `https://abdl-space.top/app`, valid HTML content, language `zh`;
- complete required mentions/tags/emojis and empty `media_attachments`, application
  data and converter defaults. Missing media attachments can cause a null access in
  `Status.postprocess`, so a minimal arbitrary JSON object is not sufficient.

Real controller/Gson/postprocessing tests retain the notice in all timeline
responses. Tests also construct real header/text/footer display items, check string
comparator/dedup behavior, and verify that the legacy profile message-ID conversion
`Long.parseLong("-1")` does not throw. These are construction/contract tests, not a
GUI click-through of the profile or message flow. Core timeline IDs remain Strings;
no numeric conversion was found in Home merge, status lookup, or display item IDs.

Existing older APKs still expose normal status/profile actions on such a notice.
The backend must reserve both IDs, reject their read/mutation/relationship routes
without resolving real records or performing writes, and never create a real user
for this sentinel. The negative numeric account ID avoids the existing legacy
`ProfileFragment` send-message numeric-ID crash that a textual account ID would
cause. Some public/list/other pagination paths use the last status ID as `max_id`,
so the backend update gate must precede ordinary cursor validation and safely
handle repeated notice requests. No broad Android action behavior is rewritten.

## Test and build execution

Builds use the existing local SDK `/home/ZYongX/Android/Sdk`, JDK
`/usr/lib/jvm/java-21-openjdk`, Gradle wrapper 8.11.1, and existing AGP/Kotlin versions.
Test invocations and the initial release attempt use the worktree project path plus:

```sh
--max-workers=1 --no-parallel --no-daemon \
-Dorg.gradle.jvmargs='-Xmx768m -Dfile.encoding=UTF-8' \
-Pkotlin.compiler.execution.strategy=in-process
```

The test worker retains its existing 512 MiB heap, one fork at a time, and per-class
forking. Commands are run sequentially rather than overlapping Gradle processes.
Shared-host RAM/swap pressure delayed the first build; an API connection
interruption was resumed by inspecting the existing process rather than starting
a duplicate build. The initial release attempt failed at `mergeDexRelease` with
D8 `OutOfMemoryError: Java heap space` under the 768 MiB heap, not a compiler,
signing, or source-contract failure. The incremental retry uses
`-Dorg.gradle.jvmargs='-Xmx1536m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'`
with the same one-worker/sequential settings; no persistent build configuration
or toolchain is changed for this resource adjustment.

The initial targeted run executed 36 cases: 34 passed, 2 failed because the new
display-item test had not initialized AppKit's `V` application context. The test
setup now calls `V.setApplicationContext`; the failure was test initialization,
not a production rendering change. The new class has 18 methods, each run on
Robolectric API 26 and API 28. A test method exercising all notice constructors
contains multiple HTTP round trips; those are not counted as additional test cases.

Corrected targeted command
`:mastodon:testDebugUnitTest --tests org.joinmastodon.android.api.AppVersionReportingHttpTest`
completed successfully: **36 passed, 0 failures, 0 errors, 0 skipped**. The corrected
run took 2m 23s. Evidence: `build/app-version-targeted-tests-fixed.log` and preserved
`build/app-version-targeted-tests-fixed.xml`.

Full `:mastodon:testDebugUnitTest` completed successfully in the combined regression
command (16m 13s): **551 passed across 74 suites, 0 failures, 0 errors, 0 skipped**.
This includes the new 36 executions; targeted passes are not added to this total.
Evidence: `build/app-version-full-tests.log` and
`mastodon/build/test-results/testDebugUnitTest/TEST-*.xml`.

The same command requested `:reader-core:testDebugUnitTest` and
`:novel-editor-core:testDebugUnitTest`, but Gradle considered those tasks up-to-date.
Their cached reports show 32 and 11 passing cases respectively; they are not claimed
as newly executed by that invocation. The main agent subsequently reran both library
test tasks with `--rerun-tasks`: reader-core **32/32** and novel-editor-core **11/11**
passed with no failures, errors or skips. The fresh run completed in 3m 24s; evidence
is `build/app-version-library-tests-fresh.log` and each library's current XML reports.
The initial and corrected logs remain under the worktree `build/` directory.

The signed Release retry completed successfully in 3m 53s. The main agent verified
APK metadata directly with aapt2: package `top.abdl_space.app`, versionCode **30**,
versionName **3.0.0**, minSdk **26**, targetSdk **35**. APK Signature Scheme **v2**
verification passed with the existing certificate SHA-256
`fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`.
The independently named delivery APK SHA-256 is
`dfd2d058038994ca19cc8283014283b0613dc266e55096a74090d87528731409`.
Evidence: `build/app-version-release-build-retry.log`,
`build/app-version-apk-badging.log` and `build/app-version-apk-signature.log`.

## Runtime acceptance limitations

Android MCP preflight reports Linux desktop emulation unsupported/nonfunctional,
zero AVDs, and zero connected ADB devices. Its default SDK is `/opt/android-sdk`,
not the working local build SDK. No SDK licenses were accepted, SDKs replaced,
AVDs created, apps installed, or devices modified.

The HTTP regression uses the real `MastodonAPIController`, real request
constructors, Gson and DTO postprocessing. Test-only ordinary-client URL rerouting
sends requests to MockWebServer loopback HTTP and preserves their method, path,
query, headers and body; controller request/response methods are not mocked.
It does **not** validate real TLS, Android certificate stores, physical network
behavior, real server authentication/policy/metrics, OEM ART behavior, rendered
GUI, accessibility, physical camera/sensors, or profile/footer interaction acceptance.
Robolectric API 26 compatibility is not a claim of real-device or emulator testing.

Only the uniquely named `ABDL-Space-3.0.0-code30.apk` in the worktree release build
output is a delivery artifact. Generic build output may be regenerated normally;
the code29 delivery copy and original workspace's root legacy APK remain untouched.
Copying the new artifact to the original workspace is reserved for the main agent.
No commit, push, deployment, or installation is performed by this Android task.
