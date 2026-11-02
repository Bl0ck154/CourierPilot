# Android 13+ restricted settings when installing an APK

CourierPilot is distributed as a signed APK on GitHub. On Android 13 and newer, Android may restrict sensitive permissions for apps installed outside a recognized app store. If the **Notification access** or **Accessibility** switch is disabled with a "Restricted setting" warning, this may be the reason.

**Only change this setting if you understand the risks and trust the APK and its publisher.** Accessibility can read screen content, and notification access can see incoming notifications. Never enable these permissions for a file from an unknown mirror or an unverified sender.

1. First try to enable the needed permission from the CourierPilot Home screen. If Android blocks it as restricted, close the warning.
2. Open Android **Settings → Apps → CourierPilot → More (⋮) → Allow restricted settings**. On some phones this action is only visible after you first attempt the blocked permission.
3. Follow Android's confirmation. Return to CourierPilot and enable the intended **Notification access** or **Accessibility** permission using its Home screen shortcut.
4. Verify that the status on Home is no longer asking for that permission. You can revoke access later in Android Settings.

Menu labels and availability differ between Android manufacturers. This is an Android security setting, **not** a CourierPilot feature or an automatic bypass. The app cannot grant itself privileged permissions.

For background and the security warning, see [Google's official Android Help page](https://support.google.com/android/answer/12623953). Install only from the [official GitHub releases](https://github.com/Bl0ck154/CourierPilot/releases/latest); the release page also provides an APK SHA-256 checksum. Checking a checksum confirms file integrity relative to that release, not that every requested permission is safe.

If the device offers no trusted path to enable the permission, do not try to defeat device policy, particularly on managed or supervised phones. You can still review locally stored data, but automatic capture may be unavailable without the required Android access.
