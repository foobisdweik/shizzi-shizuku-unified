# Keep-alive behavior and validation

Shizzi keeps the CPU awake during startup and active sessions.
The Advanced settings contain the switch. It defaults to enabled.
The existing Permissions section shows whether battery exemption is active.
Stopping or losing a session releases the wake lock.
Shizzi does not recreate a lost session automatically.

Shizuku has a separate switch under Settings: Keep running and recover.
It defaults to disabled. Enabling it starts an ongoing notification and holds a wake lock.
This protects the manager. It cannot guarantee that Samsung preserves the shell server.
Recovery uses the existing wireless pairing. It does not change authorization timeouts.
If wireless debugging is disabled, automatic enabling requires `WRITE_SECURE_SETTINGS`.
The setup row explains the required command and opens battery settings.
On Samsung, remove both apps from sleeping apps and permit unrestricted battery use.

Recovery detects server death through a listener and checks every ten seconds.
It permits one attempt at a time. Each attempt has a fifteen-second deadline.
Failures use delays of 2, 5, 15, 30, then 60 seconds.
Without Wi-Fi, recovery waits. Missing pairing requires user action.
The recovery starter refuses to replace an existing server.
An explicit Stop disables recovery after confirmation.
Disabling protection stops its service and releases its wake lock.
Recovery does not restore user services. Restart an interrupted Shizzi session manually.

## Phone checks

1. Record the phone build and the installed app versions.
2. Confirm that both apps open without a crash.
3. Enable protection in Shizuku and verify its notification and wake lock.
4. Stop protection and verify that the service and wake lock disappear.
5. Start Shizuku through wireless pairing and record the server process identifier.
6. Invoke its recovery starter while the server runs. Confirm that the identifier stays unchanged.
7. Kill only the server during a controlled test. Confirm that recovery starts exactly one server.
8. Disable Wi-Fi during recovery. Confirm that retries wait until Wi-Fi returns.
9. Remove pairing during a controlled test. Confirm that Shizuku requests pairing instead of repeatedly reconnecting.
10. Start a protected Shizzi session. Confirm that traffic continues after the screen turns off.
11. Stop the session. Confirm that its wake lock disappears.
12. Repeat with protection disabled to obtain a comparison.

Run the screen-off comparisons for thirty minutes each.
Then run the combined protection for two hours.
Record interruptions, recovery times, battery changes, and cleanup after failures.
Do not treat build success or installation as proof that these phone checks passed.

## Results on 2026-11-09

The phone is an SM_A176U1 running Android 16. Its One UI property is `80500`.
Both final debug APKs were installed through adb. Their installed hashes match the build artifacts.
Shizzi passed its build, all 145 unit tests, and lint with no errors.
Shizzi initially showed a warning because its Go library used 4 KB alignment.
The corrected build passes native and ZIP checks for 16 KB alignment.
A fresh launch reaches Welcome without the compatibility warning.

Shizuku passed its APK build and both recovery tests through Gradle.
Its ARM64 native libraries use 16 KB alignment. Its APK signature verifies.
The new protection setting appears in Settings.
Enabling protection starts its foreground service and acquires `Shizuku:protection`.
Disabling protection removes that service and releases the wake lock.
Protection was left disabled after this temporary test. Notifications were allowed during the test.
The server starts through USB adb. A second `--recover` invocation preserves the existing server process.
The server was left running after this check.

Shizuku full lint remains blocked by 124 errors in existing declarations.
These comprise 115 missing translations, seven invalid string formats, one missing quantity, and one television-launcher declaration.
The seven API errors introduced by the new service were corrected.
No lint baseline or global suppression was added.

Automatic recovery through wireless pairing remains unverified.
The thirty-minute comparisons and two-hour tethering test remain unverified.
No tethering session was started, and no provider was installed during these checks.

## Follow-up after the screen-off failure

The 11:13 failure followed an Android USB configuration change when the phone locked.
The protection service and wake lock survived, but adbd restarted and Shizzi lost its helper.
Recovery then failed because Android required a physical Wi-Fi connection.
The corrected Shizuku build rejects Shizzi's virtual Wi-Fi network for recovery.
Its build and both recovery tests passed. The corrected APK is installed and its hash matches.

With user approval, Shizzi was stopped and USB was set to No data transfer.
A fresh Shizuku server survived more than two minutes with the phone locked and dozing.
Both the server PID and adbd PID stayed unchanged.
USB remains in No data transfer mode. Shizuku is running, and Shizzi is stopped.
Long screen-off tethering and operation without a USB cable remain unverified.
