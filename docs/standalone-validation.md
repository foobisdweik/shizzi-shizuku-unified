# Standalone build validation

Validated locally on 2026-11-09. The A17 was disconnected during the initial build. It reconnected for installation and the checks below.

## Build and checks

The following command passed with JDK 21 and the local Android SDK:

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

- All 180 unit tests passed across 30 suites. None were skipped.
- Lint reported no errors and 78 warnings. No new global suppression or baseline was added.
- Both APKs contain the preserved `HelperMain` and `HelperProcess` entrypoints.
- Neither APK contains classes in `rikka.shizuku` or `moe.shizuku`.
- Every packaged native library has 16 KB alignment for all LOAD segments.
- The debug APK passed the Android SDK's ZIP alignment check for 16 KB pages.
- The debug APK passed signature verification. Its signature uses the local debug key.
- The release APK passed shrinking and packaging. It is unsigned and is not the installation artifact.
- `git diff --check` passed.

The signed installation artifact is `app/build/outputs/apk/debug/app-debug.apk`.
Its version is `0.6.0-standalone`, with version code `8` and package `dev.shizzi`.

The debug APK has SHA-256:

```text
2843330010369e1c1a1485e228ae7d19ef557fec805215c3f18aad7add3fb968
```

## Tests and their limits

New tests cover protection defaults and persistence, recovery schedules and Stop decisions, ownership records, caller rules, socket cancellation, packet redaction, USB parsing, and bounded command output.

Caller-rule tests check the permission decisions in host tests. They do not replace testing Binder transactions from another app on Android.

## A17 installation and runtime checks

The signed debug APK was installed successfully over version `0.5.0` through USB debugging.

The first device run exposed a package attribution error. Android rejected `ContentResolver.call` because the shell process had no bound app, so Android attributed its provider request to `android`.

The corrected helper uses the framework's external-provider interface with explicit shell attribution. Provider and Binder caller checks remain in place. The corrected APK was rebuilt, installed, and reached Ready.

Shizuku's app and server were stopped. Shizzi started Wi-Fi tethering through its dedicated helper. Android reported `testtun15` as the sole upstream. The Go library loaded and attached its datapath.

The saved USB tethering mode was Auto. Starting that mode changed the USB configuration, restarted `adbd`, and killed the helper. Both the app and logs reported the failure.

For the isolated Wi-Fi test, USB tethering was changed to Never. The phone's USB mode was already No data transfer. No optional phone-wide protection switch was enabled.

A Wi-Fi-only session remained active with the phone locked from 16:26:28 to 16:28:24. The helper PID and `adbd` PID remained unchanged. Shizuku's app and server were absent. This was a bounded 116-second screen-off check, not a thirty-minute test.

A forced helper death then exposed two cleanup errors. Recovery used an unrebound shell context, so Android attributed its stop request to `android`. A fresh session object could then overwrite an earlier ownership record when Stop ran with an empty in-memory set.

Both errors were corrected. Recovery now uses the same rebased shell context as normal tethering. Empty in-memory ownership no longer overwrites earlier records. Two regression tests were added.

Manual Start now uses an explicit service action, so it does not depend on automatic resumption being enabled. Phone-wide authorization settings use Android's settings CLI with shell attribution.

The latest corrected APK is installed and its SHA-256 matches the local artifact. After the phone was unlocked, the leftover hotspot was turned off in Android Settings.

On the corrected build, a new Wi-Fi session used `testtun17`. The helper was forcibly killed. After the helper was relaunched through USB, it cleaned up the recorded hotspot. Shizzi automatically resumed the requested session on `testtun18`.

A second forced helper death tested Stop during recovery. Stop cleared the saved session request. After USB relaunched the helper, the hotspot stopped and remained off. At 16:40:34, Android reported no tethered interface and no upstream request. The helper remained alive without restarting tethering.

A fresh session then started on `testtun19`. Shizuku's app and server remained stopped throughout these corrected-build checks.

The final installed build was locked at 16:41:14. At the final check at 16:43:42, the phone remained locked and dozing. Helper PID `22210`, app PID `20768`, and `adbd` PID `17193` remained unchanged. Android still reported `testtun19` as the tethering upstream, and the partial wake lock remained held.

The phone was left running this Wi-Fi-only session. USB tethering remains set to Never. This is a bounded test of over two minutes. Thirty-minute operation and operation with the USB cable removed remain unverified.

These checks verify automatic session resumption after external helper startup. They do not verify automatic startup through Shizzi's own wireless pairing.

The following checks remain unverified:

- Restoring each phone-wide setting.
- Remaining connected for thirty minutes with the screen locked, with and without USB.
- Reconnecting after a reboot or an APK update.
- End-to-end traffic from a tethered client.

USB tethering remains affected by the observed `adbd` restart. The successful Wi-Fi test does not resolve that limitation.

The helper calls `setsid()` to detach from its debugging shell. This does not prove survival of an `adbd` restart.

See [standalone setup](standalone-shizzi.md) for setup steps and the complete settings list.

## Wireless pairing and automatic recovery

Shizzi paired successfully through its own UI on 2026-11-09. Android Settings and Shizzi were placed in split screen so the pairing dialog stayed open. Shizzi used its own encrypted credentials. The temporary pairing values were removed after submission.

This device check exposed two startup problems. A nested recovery timeout could terminate the protection worker. The worker now treats an attempt timeout as a retryable failure while still respecting external cancellation. Two coroutine tests cover those cases.

The Java background command could not start a surviving helper through the legacy wireless debugging shell. The corrected build includes `libshizzi_starter.so`. This native starter detaches before starting Java and confirms execution through a bounded pipe. The APK extracts the executable to its native directory. All five packaged native files retain 16 KB alignment.

The corrected build was installed. At 17:04:55, Shizzi's own client authenticated through TLS and sent its native startup command. The helper appeared without an external USB startup command. Shizzi resumed tethering through `testtun20`.

At 17:05:19, helper PID `28967` was forcibly killed. Shizzi automatically connected through wireless debugging and started helper PID `29373`. It resumed tethering on `testtun21`, which was verified as the sole upstream at 17:05:27. This recovery took about eight seconds.

A further forced helper death tested Stop during wireless recovery. The replacement helper started automatically and cleaned up tethering. Android reported no upstream request and no tethered interfaces. Tethering did not resume after Stop.

A fresh Wi-Fi session was started after these checks. Shizuku's app and server stayed stopped. Wireless debugging remained enabled on the current Wi-Fi network. The USB cable remained connected for installation, fault injection, and observation. The successful recovery used Shizzi's local wireless client, not an external USB startup command.

The native starter rejected an attempt to run it under Shizzi's ordinary app UID. It requires shell UID `2000`.

The final build passes 180 unit tests and lint reports no errors and 78 warnings. Both debug and release builds pass. The installed debug APK matches the local artifact.

These results do not verify recovery when Android has disabled wireless debugging, operation after removing the USB cable, or thirty-minute endurance. The optional Restore wireless debugging control was not enabled during this test.
