# Shizzi keep-alive results

Verified on 2026-11-09.

## Build and automated checks

The following Gradle tasks passed:

- `:app:assembleDebug`
- `:app:testDebugUnitTest`
- `:app:lintDebug`

The unit tests passed: 145 tests across 23 suites, with no failures or errors.
Lint passed with 0 errors and 46 warnings.
One warning concerns the wake lock without a fixed timeout.
The lock intentionally lasts for the active session. Session termination and service destruction release it.
The new tests cover repeated updates, settings changes, concurrent acquisition, and callbacks after destruction.

## Installation

Android Debug Bridge installed `dev.shizzi` version `0.5.0` successfully on the connected `SM_A176U1`.
The installed APK is `app/build/outputs/apk/debug/app-debug.apk`.
Its SHA-256 is `059576419c2f54d3363873a76a30ba40dd0f8e11a74a8e5d1c57b2feb444f8f5`.

The first APK displayed an Android compatibility warning because `libgojni.so` used 4 KB alignment.
The build now passes both 16 KB linker flags to gomobile.
These flags follow the [Android instructions for NDK r27](https://developer.android.com/guide/practices/page-sizes).
The corrected APK was rebuilt and installed successfully.
Every LOAD segment in all three native libraries now has 16 KB alignment.
The APK passes `zipalign -c -P 16 4`.
The phone currently uses 4 KB pages, so these checks do not establish runtime behavior with 16 KB pages.

## Remaining phone checks

No tethering session was started during this validation.
Session survival with the screen off and battery use remain unverified.
The longer comparisons remain listed in `docs/keep-alive-validation.md`.
