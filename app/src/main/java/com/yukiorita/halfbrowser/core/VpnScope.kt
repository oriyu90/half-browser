package com.yukiorita.halfbrowser.core

/**
 * Rewrites a WireGuard configuration so that only Half-Browser is routed.
 *
 * How the scope is enforced
 * -------------------------
 * The WireGuard library turns `IncludedApplications` / `ExcludedApplications`
 * from the `[Interface]` section into `VpnService.Builder.addAllowedApplication`
 * / `addDisallowedApplication`. Per the Android documentation, calling
 * `addAllowedApplication` at least once means **only** the listed apps use the
 * tunnel and every other app keeps the normal network.
 *
 * The app therefore uses an **allow-list containing its own package**. Two
 * properties make this the right choice over "exclude every other package":
 *
 *  - **No package enumeration.** `PackageManager.getInstalledPackages()` is
 *    filtered by package visibility on Android 11+, so an exclusion list built
 *    from it would silently miss apps — and a missed app would be routed through
 *    the browser's tunnel. The allow-list needs exactly one name that is always
 *    known: our own.
 *  - **Fails closed.** Any app installed later is outside the tunnel by default.
 *
 * The app's WebView renderer processes share the app's UID, so page traffic is
 * covered by the same entry.
 *
 * A user-supplied scope in a pasted config is removed: honouring it could route
 * other apps through this browser's tunnel, which is exactly what the feature
 * promises never to do.
 *
 * Pure and I/O-free so the rewrite rules are unit-tested;
 * [com.yukiorita.halfbrowser.data.VpnController] supplies the package name.
 */
object VpnScope {

    const val INCLUDED_KEY = "IncludedApplications"
    const val EXCLUDED_KEY = "ExcludedApplications"

    /**
     * @param configText a validated WireGuard config
     * @param ownPackage this app's package name
     * @return the config with the scope keys replaced by an allow-list of [ownPackage]
     */
    fun applyAppOnlyScope(configText: String, ownPackage: String): String =
        applyScope(configText, listOf(INCLUDED_KEY to ownPackage))

    /**
     * Replaces every scope key with [scope] and drops the originals.
     * [scope] is a list of (key, package) pairs, emitted one per line.
     */
    fun applyScope(configText: String, scope: List<Pair<String, String>>): String {
        val lines = configText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>(lines.size + scope.size)
        var inInterface = false
        var inserted = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inInterface = trimmed.equals("[Interface]", ignoreCase = true)
                out += line
                if (inInterface && !inserted) {
                    scope.forEach { (key, pkg) -> out += "$key = $pkg" }
                    inserted = true
                }
                continue
            }
            if (inInterface) {
                val key = trimmed.substringBefore('=').trim().lowercase()
                if (key == INCLUDED_KEY.lowercase() || key == EXCLUDED_KEY.lowercase()) continue
            }
            out += line
        }
        return out.joinToString("\n")
    }

    /** Packages that must stay outside the tunnel for the system UI to work. */
    fun isSystemExempt(packageName: String): Boolean =
        packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName.startsWith("com.android.systemui.")
}
