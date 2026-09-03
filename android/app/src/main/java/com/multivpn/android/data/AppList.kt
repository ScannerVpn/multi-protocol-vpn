package com.multivpn.android.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Installed-app inventory for the split-tunnel picker — the Android
 * counterpart of the desktop's `vpn.core.AppList` (which enumerates .exe
 * process names instead).
 *
 * Only apps that can actually use the network are listed: an app without
 * INTERNET permission cannot be affected by a tunnel, so offering it would be
 * a choice with no effect. Our own package is excluded too — routing MultiVPN
 * through its own tunnel is a loop.
 */
object AppList {

    data class Entry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
    )

    /**
     * @param includeSystem when false (default) hides system apps, which is
     *        what a user picking "which of my apps use the VPN" expects; the
     *        toggle exists because some system apps (a browser shipped with the
     *        ROM) are legitimately the target.
     */
    fun installed(context: Context, includeSystem: Boolean = false): List<Entry> {
        val pm = context.packageManager
        val self = context.packageName
        val packages = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        return packages.asSequence()
            .filter { it.packageName != self }
            .filter { includeSystem || !isSystem(it) }
            .filter { hasInternet(pm, it.packageName) }
            .map { info ->
                Entry(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = isSystem(info),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun isSystem(info: ApplicationInfo): Boolean =
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    /** An app with no INTERNET permission cannot be tunneled either way. */
    private fun hasInternet(pm: PackageManager, pkg: String): Boolean = runCatching {
        val perms = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions
        perms?.contains(android.Manifest.permission.INTERNET) == true
    }.getOrDefault(false)
}
