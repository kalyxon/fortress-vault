# Fortress Vault Installation Guide

Fortress Vault is a native Kotlin and Jetpack Compose Android application. It uses Android Device Owner APIs to suspend and hide selected applications, so installation requires a dedicated test phone or a phone that can be factory reset.

## Requirements

- Android Studio Koala or newer
- JDK 17
- Android SDK Platform 34
- Android SDK Build-Tools compatible with SDK 34
- Android platform-tools, including `adb`
- A physical Android device running Android 8.0 (API 26) or newer
- A USB cable for initial provisioning

Fortress Vault is not intended to be installed on a personal phone containing accounts or important data. Device Owner provisioning requires a fresh device state.

## Build The APK

1. Open the `FortressVault` directory in Android Studio.
2. Allow Android Studio to finish the Gradle sync and download the required dependencies.
3. Build the debug APK:

   ```bash
   ./gradlew assembleDebug
   ```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat assembleDebug` instead.

## Prepare The Device

> **Warning:** Factory reset erases the device. Back up anything that must be kept before continuing.

1. Factory reset the Android device.
2. Complete the initial device setup without adding a Google account or other account.
3. Do not restore a device backup during setup.
4. Open **Settings > About phone** and tap **Build number** seven times to enable Developer options.
5. Open **Developer options** and enable **USB debugging**.
6. Connect the phone to the computer.
7. Accept the USB debugging authorization prompt on the phone.
8. Verify that `adb` can see the device:

   ```bash
   adb devices
   ```

The device should appear with the status `device`, not `unauthorized` or `offline`.

## Install And Provision Fortress Vault

Install the debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Assign Fortress Vault as the Device Owner:

```bash
adb shell dpm set-device-owner com.fortress.vault/.FortressAdminReceiver
```

A successful command reports that the active admin component was set as the device owner. If the command fails because the device already has accounts or a device owner, factory reset the device and repeat the preparation steps.

Launch the app:

```bash
adb shell monkey -p com.fortress.vault 1
```

Alternatively, open **Fortress Vault** from the device launcher.

When the app displays the setup screen, tap **I've Run The Command**. The app checks Device Owner status before allowing the vault setup to continue.

## Seal Applications

After Device Owner status is confirmed:

1. Follow the in-app setup flow.
2. Select the applications Fortress Vault should block.
3. Configure the unlock time and recovery phrase as prompted.
4. Review the selected applications carefully.
5. Seal the vault.
6. Store the recovery phrase somewhere secure and offline.

Sealed applications may be hidden or suspended and the Sentinel foreground service will enforce the configured state. Android may show a persistent notification for this service.

## Install A Release APK

Build the unsigned release APK with:

```bash
./gradlew assembleRelease
```

The output is written to:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

The release build is not configured with a signing key. To distribute it beyond local testing, configure a private signing key in the Android Gradle configuration and keep credentials outside source control. For a local device test, the debug APK is the simplest option.

## Troubleshooting

### `adb devices` shows `unauthorized`

Unlock the phone and accept the USB debugging prompt. If no prompt appears, revoke USB debugging authorizations in Developer options, reconnect the cable, and run `adb devices` again.

### `dpm set-device-owner` fails

Device Owner provisioning normally fails when the device has an account, an existing device owner, or a previously provisioned management profile. Factory reset the phone, skip account restoration, and run the command before adding any accounts.

### The app says Device Owner was not detected

Confirm that the provisioning command completed without an error and that the package name and receiver are exact:

```text
com.fortress.vault/.FortressAdminReceiver
```

Then return to the app and tap **I've Run The Command** again.

### Gradle cannot find Java

Check the active Java version:

```bash
java -version
```

Use JDK 17. In Android Studio, select it under **Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK**.

### Gradle dependencies cannot be downloaded

Check the computer's network connection and confirm that Android Studio can access Google Maven, Maven Central, and the Gradle distribution service. The wrapper uses Gradle 8.7.

## Important Limitations

- This project is not Play Store distributable as-is. Device Owner and package-query capabilities are subject to Android Enterprise and Play policy requirements.
- A recovery-mode factory reset cannot be completely blocked by a third-party Device Owner application.
- Behavior can vary across Android versions and device manufacturers, especially around persistent policy state and background execution.
- Test the complete seal and emergency-unlock workflow on the exact Android device model before relying on it.
