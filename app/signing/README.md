# Persistent Debug Signing Key

## Why `persistent-debug.keystore.base64` Exists

When an Android project is exported to GitHub and imported into another Google AI Studio account, AI Studio preserves the project's `applicationId` (`com.aistudio.mkpdfreader.app`), but may regenerate the root `debug.keystore.base64` for the new Google account.

If the debug signing certificate changes while `applicationId` stays the same, Android refuses to install updates over an existing APK (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), requiring an uninstall and wiping local app data.

Pinning the Base64-encoded debug keystore inside `app/signing/persistent-debug.keystore.base64` ensures that Gradle (`:app:syncPersistentKeystore`) automatically syncs `${rootDir}/debug.keystore` and `${rootDir}/debug.keystore.base64` before building, keeping the signing certificate identical across accounts and machines.

## Restoring an Older Account's Signing Key

If you need to upgrade over an existing installation signed by an older AI Studio account without uninstalling:

1. Copy the Base64 contents of the older account's `debug.keystore.base64` file.
2. Paste it into `app/signing/persistent-debug.keystore.base64` (replacing the current contents).
3. Rebuild the project (`assembleDebug` or run the app); `:app:syncPersistentKeystore` will decode and apply it automatically.
