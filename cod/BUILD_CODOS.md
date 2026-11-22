# Building the CoD OS placeholder (`codos` flavor) and its Magisk module

Result: a system app (`android.uid.system`, no root), package `com.andrerinas.headunitrevived`, signed with the CoD
platform key, plus a Magisk module that places it in `/system/priv-app/OpenHeadunit/`. This is a **placeholder**: no UI
redesign, no licence check, no firmware binding (contract §0). Root → system mapping: `cod/ROOT_TO_SYSTEM.md`.

## Build

```powershell
tools\build_codos.ps1          # gradle assembleCodosRelease, verify the signer, pack dist\OpenHeadunit_codos_<version>.zip
tools\build_codos.ps1 -SkipBuild   # re-pack the module from the APK already staged
```

The script reads `keystore.password=` / `key.password=` from `C:\Users\kosty\Community_of_Devs\README_UKR.txt` into
environment variables of its own process (`CODOS_KEYSTORE_PASSWORD`, `CODOS_KEY_PASSWORD`; the keystore file and alias
travel in `CODOS_KEYSTORE_FILE`, `CODOS_KEY_ALIAS`). Gradle reads only the environment. Nothing secret is printed, logged
or committed. It then runs `apksigner verify --print-certs` and refuses to pack unless the signer SHA-256 starts
`766232df` and ends `ad9e`.

A debug or unit-test build of `codos` needs no key: `gradlew :app:testCodosDebugUnitTest`.

## Module layout (`magisk/`, LF line endings enforced by `.gitattributes`)

```
module.prop                                  id openheadunit_codos
customize.sh                                 set_permissions(): 0 0 0755 dirs, 0 0 0644 files, u:object_r:system_file:s0
META-INF/com/google/android/update-binary    the standard Magisk module installer
META-INF/com/google/android/updater-script   #MAGISK
system/priv-app/OpenHeadunit/OpenHeadunit.apk        (built, not in git)
system/etc/permissions/privapp-permissions-openheadunit.xml
```

## Install notes (qf001 / head lead; not done here)

- Never `adb install` this app: a copy in `/data/app` hides the system copy (and another signer is refused). The 4.1.0-beta1
  on stand 198 (`/data/app`, debuggable, another signer) has to be removed first; that is the owner's decision.
- `ro.control_privapp_permissions=enforce` on the stand: `privapp-permissions-openheadunit.xml` lists every privileged
  permission the APK requests. After any manifest change re-read the merged manifest and update the XML before building the
  module.
- 📻 Not verified on a unit: that the app boots as uid 1000 under the A14 sepolicy, that `pm`/`am`/`svc` run from its
  domain, and that the compressed native libraries (`extractNativeLibs=true`, minSdk 21) load from the overlaid
  priv-app. If the libraries fail to load, build with `useLegacyPackaging = false` (stored libraries, mapped from the APK).

## Tests

`gradlew :app:testCodosDebugUnitTest`: 5037 tests, 1 failure, and that failure is upstream's, not from this work:
`SettingsStringLimitsTest` fails on `auto_reconnect_acc_on_desc` (109 characters, limit 80), introduced by upstream commit
`c0da0989`; the `github` flavor fails the same test. The new `CodosRootFreeTest` passes (6/6).
