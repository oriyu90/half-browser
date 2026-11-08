package com.yukiorita.halfbrowser.data

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.yukiorita.halfbrowser.core.VpnConfigError
import com.yukiorita.halfbrowser.core.VpnProfile
import com.yukiorita.halfbrowser.core.VpnScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream

/**
 * Lifecycle of the in-app WireGuard tunnel.
 *
 * Scope guarantee — "this VPN is only for this app's traffic"
 * ----------------------------------------------------------
 * The tunnel is a normal Android `VpnService`, so it is routed per UID. Before
 * bringing it up, every *other* installed package is added to the builder's
 * disallowed list, leaving only Half-Browser inside the tunnel. Doing it by
 * exclusion (rather than `addAllowedApplication(ownPackage)`) means:
 *  - the app's own traffic is always covered, even if the package list changes;
 *  - a package installed later is outside the tunnel by default, which is the
 *    safe direction for a browser-only VPN.
 *
 * The exclusion is applied through the WireGuard library's own config fields
 * (`Interface.getExcludedApplications()`), so no fork of the library is needed:
 * [withAppOnlyScope] rewrites the `[Interface]` section to carry the list, and
 * the library calls `Builder.addDisallowedApplication` for each entry.
 *
 * Threading: every backend call runs on [Dispatchers.IO] because starting a
 * tunnel performs DNS resolution and a native hand-off. State is published on a
 * [StateFlow] that the Compose UI collects.
 */
class VpnController(
    private val context: Context,
    private val store: VpnStore,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private var backend: Backend? = null
    private var activeTunnel: ActiveTunnel? = null

    /** True once the user granted the system VPN consent dialog at least once. */
    fun needsSystemConsent(): Boolean = VpnService.prepare(appContext) != null

    /** Intent to hand to `ActivityResultContracts.StartActivityForResult`. */
    fun consentIntent(): Intent? = VpnService.prepare(appContext)

    /**
     * Connects [profile]. [appOnly] is accepted for symmetry with the setting but
     * is always enforced: Half-Browser never routes other apps.
     */
    fun connect(profile: VpnProfile) {
        scope.launch {
            mutex.withLock {
                if (_state.value is VpnState.Connected || _state.value is VpnState.Connecting) {
                    // Tear the old tunnel down first so a profile switch cannot
                    // leave two interfaces (and two default routes) behind.
                    disconnectInternal()
                    // GoBackend calls stopSelf() when a tunnel goes down and only
                    // starts the service again once the destroyed instance has
                    // reset its static future. Bringing the next tunnel up before
                    // that race resolves makes the system destroy the *new*
                    // tunnel a moment later, so wait for the teardown to settle.
                    delay(SERVICE_TEARDOWN_MS)
                }
                _state.value = VpnState.Connecting(profile.id)
                val result = runCatching { bringUp(profile) }
                result.onFailure { error ->
                    Log.w(TAG, "VPN connect failed: ${error.javaClass.simpleName}")
                    _state.value = VpnState.Failed(profile.id, classify(error))
                    runCatching { disconnectInternal() }
                }.onSuccess {
                    _state.value = VpnState.Connected(profile.id)
                }
            }
        }
    }

    /** Disconnects and returns to [VpnState.Disconnected]. Safe to call repeatedly. */
    fun disconnect() {
        scope.launch {
            mutex.withLock {
                _state.value = VpnState.Disconnecting
                val result = runCatching { disconnectInternal() }
                _state.value = if (result.isSuccess) {
                    VpnState.Disconnected
                } else {
                    VpnState.Failed(_state.value.profileId, VpnFailure.UNKNOWN)
                }
            }
        }
    }

    /**
     * Reconnect used by the "reconnect" button: the same profile is brought down
     * and up again, which re-runs the handshake and re-resolves the endpoint.
     */
    fun reconnect() {
        val current = _state.value
        val id = current.profileId ?: return
        val profile = store.find(id) ?: run {
            _state.value = VpnState.Failed(id, VpnFailure.PROFILE_MISSING)
            return
        }
        connect(profile)
    }

    /** Called when the profile is edited or deleted while it is active. */
    fun onProfileRemoved(id: String) {
        if (_state.value.profileId == id) disconnect()
    }

    fun onProfileUpdated(profile: VpnProfile) {
        // Apply the new config immediately so an edit takes effect without a
        // surprise "old config still running" state.
        if (_state.value is VpnState.Connected && _state.value.profileId == profile.id) {
            connect(profile)
        }
    }

    /** Called when the app is leaving the foreground with the tunnel up. */
    fun onAppStopped() {
        // Kept connected: a download or an open page should not lose its route
        // merely because the user switched apps. The system shows the VPN key
        // icon, and the home screen shows the live state when they return.
    }

    private fun bringUp(profile: VpnProfile) {
        val intent = VpnService.prepare(appContext)
        if (intent != null) {
            throw ConsentRequired()
        }
        val config = parseScoped(profile.configText)
        val tunnel = ActiveTunnel(profile.id) { name, up ->
            _state.value = if (up) {
                VpnState.Connected(profile.id)
            } else if (_state.value is VpnState.Connected) {
                VpnState.Disconnected
            } else {
                _state.value
            }
            Log.i(TAG, "tunnel $name ${if (up) "up" else "down"}")
        }
        val engine = backend ?: GoBackend(appContext).also { backend = it }
        engine.setState(tunnel, Tunnel.State.UP, config)
        activeTunnel = tunnel
    }

    private fun disconnectInternal() {
        val tunnel = activeTunnel
        val engine = backend
        activeTunnel = null
        if (tunnel != null && engine != null) {
            runCatching { engine.setState(tunnel, Tunnel.State.DOWN, null) }
        }
    }

    private fun classify(error: Throwable): VpnFailure = when (error) {
        is ConsentRequired -> VpnFailure.CONSENT_REQUIRED
        is BackendException -> when (error.reason) {
            BackendException.Reason.VPN_NOT_AUTHORIZED -> VpnFailure.CONSENT_REQUIRED
            BackendException.Reason.DNS_RESOLUTION_FAILURE -> VpnFailure.DNS
            BackendException.Reason.TUN_CREATION_ERROR -> VpnFailure.TUN
            BackendException.Reason.UNABLE_TO_START_VPN -> VpnFailure.SERVICE
            BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> VpnFailure.ACTIVATION
            else -> VpnFailure.UNKNOWN
        }
        is com.wireguard.config.BadConfigException -> VpnFailure.BAD_CONFIG
        is com.wireguard.config.ParseException -> VpnFailure.BAD_CONFIG
        else -> VpnFailure.UNKNOWN
    }

    /**
     * Returns [configText] with every other package excluded from the tunnel.
     *
     * The WireGuard library reads `ExcludedApplications` from the `[Interface]`
     * section and calls `Builder.addDisallowedApplication` for each entry. The
     * original text is left untouched apart from that one key, so the rest of the
     * user's config (and any future key) is preserved verbatim.
     */
    /**
     * Parses the profile with the app-only scope applied.
     *
     * The allow-list is the primary form. If the library rejects it (a future
     * version could drop `IncludedApplications`), the exclusion form is tried so
     * the feature still works; if both fail the profile is reported as bad
     * rather than silently routing every app.
     */
    private fun parseScoped(configText: String): Config {
        val own = appContext.packageName
        // Primary: allow-list containing only this app.
        val included = runCatching {
            Config.parse(ByteArrayInputStream(
                VpnScope.applyAppOnlyScope(configText, own).toByteArray(Charsets.UTF_8),
            ))
        }.onFailure { Log.w(TAG, "allow-list scope rejected: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (included != null && included.getInterface().getIncludedApplications().contains(own)) {
            return included
        }

        // Fallback: exclude every other visible package. Only accepted when the
        // parsed config really carries the exclusion list, so a silently ignored
        // key can never leave the tunnel routing the whole device.
        val others = installedOtherPackages().map { VpnScope.EXCLUDED_KEY to it }
        val excluded = runCatching {
            Config.parse(ByteArrayInputStream(
                VpnScope.applyScope(configText, others).toByteArray(Charsets.UTF_8),
            ))
        }.onFailure { Log.w(TAG, "exclusion scope rejected: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (excluded != null && excluded.getInterface().getExcludedApplications().isNotEmpty()) {
            Log.w(TAG, "using exclusion scope (${others.size} packages)")
            return excluded
        }
        throw IllegalStateException("no app-only scope accepted")
    }

    internal fun withAppOnlyScope(configText: String): String =
        VpnScope.applyAppOnlyScope(configText, appContext.packageName)

    private fun installedOtherPackages(): List<String> = runCatching {
        appContext.packageManager.getInstalledPackages(0)
            .asSequence()
            .map { it.packageName }
            .filter { it != appContext.packageName }
            // The system UI / shell must stay reachable or the VPN key icon and
            // the notification shade break while the tunnel is up.
            .filterNot { VpnScope.isSystemExempt(it) }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    private class ConsentRequired : IllegalStateException("VPN consent required")

    private class ActiveTunnel(
        private val id: String,
        private val onState: (String, Boolean) -> Unit,
    ) : Tunnel {
        override fun getName(): String = com.yukiorita.halfbrowser.core.VpnNaming.tunnelNameFor(id)

        override fun onStateChange(newState: Tunnel.State) {
            runCatching { onState(getName(), newState == Tunnel.State.UP) }
        }
    }

    companion object {
        private const val TAG = "VpnController"
        // Longer than the observed VpnService destroy latency; still short
        // enough that a reconnect feels immediate.
        private const val SERVICE_TEARDOWN_MS = 600L

        /** Error text for [VpnConfigError] is resolved in the UI layer. */
        fun errorKey(error: VpnConfigError): String = error.name
    }
}

/** Observable tunnel state. */
sealed interface VpnState {
    val profileId: String?

    data object Disconnected : VpnState {
        override val profileId: String? get() = null
    }

    data class Connecting(override val profileId: String) : VpnState

    data class Connected(override val profileId: String) : VpnState

    data object Disconnecting : VpnState {
        override val profileId: String? get() = null
    }

    data class Failed(override val profileId: String?, val reason: VpnFailure) : VpnState

    val isActive: Boolean
        get() = this is Connected || this is Connecting
}

enum class VpnFailure {
    CONSENT_REQUIRED,
    DNS,
    TUN,
    SERVICE,
    ACTIVATION,
    BAD_CONFIG,
    PROFILE_MISSING,
    UNKNOWN,
}
