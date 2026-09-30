# 2FAS security-module adaptation

ABDL Space's PIN domain models, repository contract/mapping structure, and lockout semantics are adapted from 2FAS Android 5.6.0 at commit `119ead28ed8d3d2215afd8f55428c1586401149b`:

https://github.com/twofas/2fas-android/tree/119ead28ed8d3d2215afd8f55428c1586401149b

The adapted source retains GPL-3.0-only attribution headers. ABDL Space supplies its own storage implementation using AndroidKeyStore AES/GCM with a fresh random IV and a dedicated app-private SharedPreferences file. It does not include the former adorsys AAR or AndroidX `security-crypto`.

GPLv3 domain/repository models, Security/PIN setup/change/disable/lock UI flows, authentication tracking, biometric key/prompt structure, and related locale strings were copied and adapted. Package names, dependency injection, navigation, graphics, themes, lifecycle integration, storage, and confirmed safety defects were adjusted for ABDL Space. The Allow screenshots setting and timed screenshot worker are excluded. 2FAS artwork/branding, BUSL/develop code, and the legacy adorsys AAR are not included.

See [the Android compatibility review](../android-security-compatibility-review.md) for the subsequent API 26–35 fixes, verification evidence, and unexecuted device acceptance.
