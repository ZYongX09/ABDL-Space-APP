# 2FAS security-domain adaptation

ABDL Space's PIN domain models, repository contract/mapping structure, and lockout semantics are adapted from 2FAS Android 5.6.0 at commit `119ead28ed8d3d2215afd8f55428c1586401149b`:

https://github.com/twofas/2fas-android/tree/119ead28ed8d3d2215afd8f55428c1586401149b

The adapted source retains GPL-3.0-only attribution headers. ABDL Space supplies its own storage implementation using AndroidKeyStore AES/GCM with a fresh random IV and a dedicated app-private SharedPreferences file. It does not include the former adorsys AAR or AndroidX `security-crypto`.

Only GPLv3 security-domain code and behavior were adapted. 2FAS screenshots, artwork, branding, and code from BUSL-licensed/develop branches are excluded.
