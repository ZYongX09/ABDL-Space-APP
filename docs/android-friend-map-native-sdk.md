# Native friend-map providers

## Versions and sources

- Baidu map: `com.baidu.lbsyun:BaiduMapSDK_Map:8.2.0`, with transitive `com.baidu.lbsyun:base:8.2.0` from Maven Central. The POM advertises Apache-2.0 metadata; use of Baidu map services/data still follows Baidu platform terms and commercial licensing rules.
- Baidu AAR verification SHA-256: `bbdd358d87fa9dd080bd3848546659591d33a380a9195edb05f87c5c8988b690`.
- Baidu base AAR SHA-256: `f5331910f8e8909819dd250fbb3f9339293126344c3f2102c29e0e296e6891f7`.
- AMap official combined SDK: map `11.3.100`, search `9.8.1`, location `11.3.101`, dated 2026-09-24. Downloaded from the SDK archive linked by the official Android download page; it is not an APK-extracted library. Search and location are included in the vendor distribution but are not initialized by the application.
- AMap official archive: `https://a.amap.com/lbs/static/zip/AMap_Android_SDK_All.zip`, SHA-256 `13d22311a78ff4c133c85ceca577f9c3a36bc04e2f76be4320f20211f5ef95a4`.
- Nested SDK archive SHA-256: `0edc8b750d6a9322ae1607c539a800ccf39c44f0c1e1a0d0875d5c646ebdeb48`.
- AMap JAR SHA-256: `b73e0d442931b6daa47332c2b8feb16ce9de4f429ef17bebe0c7b129a1da300a`.
- Matching native libraries are included for `arm64-v8a` and `armeabi-v7a`. AMap is not offered on x86/x86_64; Baidu is available on supported vendor ABIs.

The application retains vendor map logos, copyright and approval-number overlays. Native SDK availability does not establish commercial authorization; confirm platform console licensing before production publication.

## Credential boundaries

`BAIDU_MAP_ANDROID_AK` and `AMAP_ANDROID_KEY` are injected into Android Manifest placeholders. They are resolved from the build environment variable of the same name, falling back to the user-level file `~/.config/abdl-space/android-maps.env` (override the path with `ABDL_MAP_ENV_FILE` or `-PmapEnvFile=<path>`). Source, tests and this document contain no usable values; that file lives outside the repository. CI can use its secret store.

Because the keys reach the package as manifest placeholders, a build that cannot see them still succeeds and installs — the map tab then only reports "地图服务未配置". Two guardrails in `mastodon/build.gradle` make that failure loud: the release packaging tasks (`assembleRelease`/`bundleRelease`/`package*` and the same names for `githubRelease`/`fdroidRelease`) fail at execution time when a key is missing, and the `verifyMapKeys` task, wired as `finalizedBy` on those tasks, opens each built APK/AAB and asserts the configured values are present in the packaged manifest (binary AXML stores manifest strings as UTF-16LE). `-PallowMissingMapKeys=true` (or `ABDL_ALLOW_MISSING_MAP_KEYS=true`) is the documented bypass for deliberately producing a keyless package; debug variants are not gated. Both `.github/workflows/build_and_deploy*.yml` workflows also refuse to start without the keys, since their repository secret store is empty and they are deprecated.

Android SDK keys are public identifiers once packaged. Bind them to the release application ID `top.abdl_space.app` and the vendor-required **SHA1** signing fingerprint, plus service/quota restrictions. Debug builds use a different package and certificate and cannot be assumed to work with the release keys.

Baidu server AK/SK and AMap Web key/security secret are neither consumed nor packaged by these providers. Client keys do not grant sponsor entitlement. Missing keys produce an unavailable-state message; the production page never silently substitutes a fake map.

## Runtime behavior

- Privacy consent must be loaded/accepted before SDK objects are constructed.
- Baidu privacy initialization uses `setAgreePrivacy`, and AMap uses `updatePrivacyShow/updatePrivacyAgree`.
- Both SDKs have their own location layers explicitly disabled. The application uses a cancellable, foreground-only Android `LocationManager` fix with a 20-second timeout and a two-minute last-known freshness limit; raw fixes are neither logged nor stored by this map flow.
- Backend presence and viewport APIs use WGS84. Conversion to GCJ02/BD09LL happens only within each provider. Camera and long-press results are converted back to WGS84 before backend requests.
- Only backend-protected coordinates are drawn. Avatar loading uses the existing app image cache, bounded thumbnail requests, anonymous placeholders and cancellation at destroy.
- Marker IDs map back to the current response. Anonymous markers do not navigate to profiles. Identified markers open ProfileFragment with the current session plus `profileAccountID`.
- Camera-idle queries are debounced by 400 ms, obsolete queries are canceled and generation checked.
- User-selected provider is supported. Initialization/authentication errors and a 30-second map-ready timeout can switch once to an unattempted provider; the registry does not loop. A ready callback does not guarantee every tile successfully rendered, so live network/GPU acceptance remains necessary.
- SDK view create/resume/pause/save/destroy is forwarded. Only one map view is active at a time.

## Release boundaries

The Android implementation is committed to `develop` but is not a published app release. The user explicitly deferred the app release: do not merge these changes into Android `main`, trigger publication, upload a replacement production APK or change production version metadata without a new release instruction. The matching backend and migration were separately authorized and deployed on 2026-10-17; this does not publish the app.

Nationwide browsing has no city/region/sponsor paywall. Region buckets are metadata, not administrative city boundaries or access-control rules. Specifying a display location remains an active-sponsor benefit; this is separate from browsing. Both the Android flow and backend enforce this distinction.

The 2026-10-17 update uses `map-presence-v2`: current-version agreement must be read to the bottom and explicitly accepted before SDK initialization. Effective baby verification is required for personal map data. Unverified users may see only an empty, neutral basemap behind the verification gate after SDK consent; loading and errors remain blocked. Location writes and privacy changes carry `settings_version`; conflicts reload settings without replaying the old public intent. Offline/logout never retract a position, while explicit hiding is persistent and prevents stale uploads from reviving it.

Backend migration `0078_map_privacy_consent.sql` and the matching backend (`57af711`, active Worker version `f2550209-a07a-4964-8f29-328054315882`) were deployed on 2026-10-17, preserving existing locations and requiring explicit v2 re-consent. See the backend repository's `docs/map-v2-production-2026-10-17.md` for evidence. This is separate from the unpublished Android test package. An old backend without the v2 consent contract is intentionally rejected rather than silently accepting outdated permission semantics.

No online USB device or usable emulator was available during preflight. Native GPU rendering, real SDK key authentication, liquid shape morphs, dark glass parity, gesture arbitration, dual-account visibility, coarse-permission behavior, pinned-location sponsor expiry and vendor failure switching require real-device acceptance. Compile/unit tests alone do not prove those behaviors.

## Official references

- https://lbsyun.baidu.com/docs/android?title=androidsdk/guide/create-map/showmap
- https://lbsyun.baidu.com/docs/android?title=androidsdk/guide/render-map/point
- https://lbsyun.baidu.com/docs/android?title=androidsdk/guide/create-project/androidstudio
- https://lbs.baidu.com/index.php?title=openprivacy
- https://lbs.baidu.com/index.php?title=open/law
- https://lbs.amap.com/api/android-sdk/download
- https://lbs.amap.com/api/android-sdk/guide/create-map/show-map
- https://lbs.amap.com/api/android-sdk/guide/create-project/dev-attention
- https://lbs.amap.com/api/android-sdk/guide/create-project/get-key
- https://lbs.amap.com/api/android-sdk/guide/draw-on-map/draw-marker
- https://lbs.amap.com/home/terms/
- https://lbs.amap.com/pages/privacy/
