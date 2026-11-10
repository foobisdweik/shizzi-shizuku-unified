# Standalone Shizzi

Shizzi starts its own privileged helper, so it no longer requires the Shizuku app. You can keep Shizzi pinned during ordinary use.

The helper runs as Android's shell user. This gives it the permissions that ordinary apps lack for tethering operations. The helper remains a separate process, but it comes from Shizzi's installed APK.

Initial setup still requires Android Settings or an authorized computer. App pinning does not remove that requirement.

## Initial setup

1. Stop any session that the older build started.
2. Turn off any hotspot that the older build left active.
3. Install the standalone build over the existing Shizzi installation.
4. Open Shizzi's helper setup.
5. Choose wireless pairing or USB startup below.
6. Allow notifications and review the battery exemption in Shizzi's permissions.
7. Start tethering after the helper reports that it is connected.

The older build did not save ownership records. Therefore, the standalone build refuses to claim tethering that is already active.

### Wireless pairing

1. Connect the phone to a real Wi-Fi network.
2. Open Developer options in Android Settings.
3. Enable Wireless debugging.
4. Select **Pair device with pairing code**.
5. Keep the pairing dialog open beside Shizzi through split screen.
6. Enter the dialog's pairing port and six-digit code in Shizzi.
7. Select **Pair and start helper**.

The pairing port differs from the connection port. Shizzi discovers the connection port after pairing.

Shizzi keeps its own pairing key. The separate Shizuku app's pairing does not automatically pair Shizzi.

The hotspot alone does not provide the Wi-Fi connection that Android requires for wireless debugging. Without that connection, automatic wireless recovery waits.

### USB startup

1. Connect the phone to a computer with Android Debug Bridge (`adb`) installed.
2. Enable USB debugging in Developer options.
3. Accept the computer's debugging authorization on the phone.
4. Select **Copy USB startup command** in Shizzi's helper setup.
5. Run the copied command on the computer.
6. Confirm that Shizzi reports a connected helper.

The command includes the installed APK's current path. Copy it again after updating or reinstalling Shizzi.

USB startup does not create a wireless pairing. Therefore, it does not establish wireless recovery for later helper failures.

## Protection controls

Fresh installations use maximum app protection. Existing choices for session wake protection survive the upgrade. Phone-wide changes remain off until you enable them.

| Control | Default | Effect |
| --- | --- | --- |
| Protect while idle | On | Shizzi keeps its protection service available without tethering. |
| Keep CPU awake during sessions | On | A wake lock keeps the CPU awake during sessions. This uses more battery. |
| Keep CPU awake while idle | On | Shizzi holds a wake lock while idle protection operates. This can use substantial battery. |
| Recover the helper automatically | On | Shizzi retries helper startup through its existing wireless pairing. |
| Resume interrupted tethering | On | Shizzi attempts to resume a previously requested session after helper recovery. |
| Start protection after reboot | On | Shizzi attempts to start protection after the first unlock. |
| Resume tethering after reboot | On | Shizzi can resume the last requested session when startup and recovery are available. |
| Check the helper every | 5 seconds | Choices are 2, 5, 10, and 30 seconds. Shorter intervals use more battery. |
| Recovery attempt limit | Unlimited | Choices are unlimited, 3 attempts, and 10 attempts. |
| Retry quickly after a failure | On | Pauses increase through 2, 5, 15, 30, and 60 seconds. Later pauses remain 60 seconds. |
| Keep the screen on | Off | Shizzi prevents screen timeout while its main activity is visible. |

When quick retries are off, pauses increase through 15, 30, 60, and 120 seconds. Later pauses remain 120 seconds.

Android can still require you to open Shizzi after a reboot. Revoked authorizations and unavailable Wi-Fi can prevent helper recovery.

### Phone-wide controls

These controls affect the phone beyond Shizzi. Each starts off and requires confirmation before you enable it.

| Control | Effect and limitation |
| --- | --- |
| Restore wireless debugging | Shizzi can restore debugging when recovery needs it. It needs a granted permission and a real Wi-Fi connection. |
| Prevent debugging authorization expiry | Shizzi prevents debugging authorizations from expiring automatically. This affects all authorized debugging devices. |
| Stabilize USB with No data transfer | Shizzi selects No data transfer to reduce USB changes during locking. This requires USB tethering to be `Never`. |
| Disable light idle | Shizzi disables a phone-wide limit on background activity. Other apps can use more battery. |
| Disable deep idle | Shizzi disables stronger limits while the phone remains unused. Battery use can increase substantially. |

Changing USB mode can disconnect debugging. Shizzi requires tethering to stop before applying that change.

Shizzi saves original values before changing these settings. It attempts restoration when you disable the control or stop all protection. Failed restoration remains pending.

If another tool changes a saved value, Shizzi avoids overwriting that later change. A saved preference does not prove that Android applied it. Check the reported protection status.

## Stop, recovery, and ownership

**Stop** clears the saved request for tethering before cleanup begins. Idle protection can remain active afterward.

**Stop tethering and all protection** clears that request and stops protection. Shizzi attempts to restore its phone-wide changes.

A helper failure preserves the request for an interrupted session. Recovery first checks the helper, then attempts to resume tethering when the settings allow it.

If tethering fails during that resumption attempt, Shizzi pauses rather than repeatedly restarting an unsafe or incompatible session. Review the error before starting again.

The helper saves which tethering types it intends to start before asking Android to start them. A replacement helper uses that record during cleanup.

The record belongs to the current boot. After a reboot, Shizzi clears the old record without stopping unrelated tethering.

Shizzi refuses to claim tethering when its state is unknown or it was already active before Shizzi requested it. Failed cleanup retains the record.

Android provides no ownership token for tethering. Therefore, the record cannot prove that another app never replaced tethering of the same type afterward.

## Verification and limits

This document describes the implementation. It does not record a completed build or a passed device test.

The verification checklist is:

1. Run the unit tests, assemble the APK, and run lint.
2. Check that the packaged app contains no required Shizuku app dependency.
3. Start through USB without the Shizuku app running.
4. Pair and start through wireless debugging.
5. Reject calls from another app and stale helper connections.
6. Interrupt the helper, then verify cleanup and automatic resumption.
7. Press Stop during recovery and confirm that tethering remains stopped.
8. Confirm that unrelated tethering is not claimed or stopped.
9. Verify restoration of each enabled phone-wide control.
10. Test locked tethering for thirty minutes.
11. Repeat the locked test without the USB cable.
12. Test protection after reboot and after an APK update.

Combining the apps does not guarantee that `adbd` will survive screen locking. A restart can still interrupt the helper.

The earlier two-app build survived a short locked test after selecting No data transfer. That result does not verify the standalone build.

A forced helper death can prevent cleanup if debugging is unavailable. Shizzi must then report that the hotspot may remain active.
