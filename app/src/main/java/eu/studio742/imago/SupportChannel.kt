package eu.studio742.imago

import android.content.Context

/** The package name of the Google Play Store app, as the installer it records. */
internal const val PLAY_STORE_INSTALLER = "com.android.vending"

/**
 * Whether the support subscription is sold through Play billing. Only a copy that Google Play installed
 * qualifies: the Play payments policy binds that copy, and it is the only one Play billing serves. The APK
 * from the GitHub release, or one installed by hand, is signed with another key and often runs where
 * there is no Play at all. With no store, the card links to the programme's page, as on the desktop.
 */
internal fun sellsSupportThroughPlay(programmeOpen: Boolean, productId: String, installer: String?): Boolean =
    programmeOpen && productId.isNotBlank() && installer == PLAY_STORE_INSTALLER

/** The package that installed this app, or null when installed by adb or when Android does not say. */
internal fun Context.installerPackage(): String? =
    runCatching { packageManager.getInstallSourceInfo(packageName).installingPackageName }.getOrNull()
