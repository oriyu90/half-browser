package com.yukiorita.halfbrowser.data

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.yukiorita.halfbrowser.BuildConfig
import com.yukiorita.halfbrowser.core.VpnAlwaysOn
import com.yukiorita.halfbrowser.core.VpnConfigError
import com.yukiorita.halfbrowser.core.VpnHealthRules
import com.yukiorita.halfbrowser.core.VpnHealthSnapshot
import com.yukiorita.halfbrowser.core.VpnProfile
import com.yukiorita.halfbrowser.core.VpnReachability
import com.yukiorita.halfbrowser.core.VpnScope
import com.yukiorita.halfbrowser.core.VpnTuning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

/**
 * Lifecycle of the in-app WireGuard tunnel.
 *
 * Scope guarantee — "this VPN is only for this app's traffic"
 * ----------------------------------------------------------
 * The tunnel is a normal Android `VpnService`, so it is routed per UID. Before
 * bringing it up, the config carries an allow-list containing only this app
 * (`IncludedApplications`), so every other app keeps the normal connection.
 * See [VpnScope] for why an allow-list is used instead of excluding packages.
 *
 * Tunnel state vs. reachability (v1.7.0)
 * --------------------------------------
 * `Connected` only means the TUN device is up. WireGuard's handshake is
 * asynchronous, so the controller additionally tracks [VpnReachability] from
 * the peers' latest handshake time ([VpnHealthRules]). A tunnel that is up but
 * has not handshaked is reported as [VpnReachability.VERIFYING] and then
 * [VpnReachability.UNREACHABLE]; one whose last handshake is old is
 * [VpnReachability.STALE]. That is what lets the home screen say "connected,
 * but nothing is flowing" instead of showing a misleading green dot.
 *
 * Threading: every backend call runs on [Dispatchers.IO] because starting a
 * tunnel performs DNS resolution and a native hand-off. State is published on
 * [StateFlow]s that the Compose UI collects.
 *
 * Concurrency: [mutex] serialises connect/disconnect/reconnect. Every async
 * transition also carries a [generation] number; a result computed for an
 * older generation is discarded instead of overwriting newer state (a slow
 * connect must not "resurrect" a tunnel the user already disconnected).
 *
 * Reconnect teardown: switching tunnels waits for the actual DOWN callback
 * (`ActiveTunnel.onStateChange(DOWN)`) rather than a fixed delay, with a
 * timeout fallback so a lost callback can never wedge the mutex.
 */
class VpnController(
    private val context: Context,
    private val store: VpnStore,
    private val settings: SettingsRepository,
    private val healthStore: VpnHealthStore? = null,
    /**
     * How to ask the OS whether our tunnel is still up. Injectable so the
     * process-restart recovery path (which otherwise needs a real device) can be
     * exercised from a unit test.
     */
    private val tunnelProbe: (Context) -> VpnTunnelProbe.State = VpnTunnelProbe::probe,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _reachability = MutableStateFlow(VpnReachability.UNKNOWN)
    val reachability: StateFlow<VpnReachability> = _reachability.asStateFlow()

    private val _health = MutableStateFlow(VpnHealthSnapshot(profileId = ""))
    /** Latest observed session facts (handshake time, last check). */
    val health: StateFlow<VpnHealthSnapshot> = _health.asStateFlow()

    private var backend: Backend? = null
    private var activeTunnel: ActiveTunnel? = null

    /** Incremented by every user-visible transition; stale work checks it. */
    @Volatile
    private var generation: Long = 0L

    /** Background poller for the handshake time; runs while a tunnel is up. */
    private var poller: Job? = null

    /**
     * Whether the user can actually see the app: the activity is started **and**
     * the screen is on.
     *
     * `MainActivity.onStop()` alone is not enough: turning the screen off does
     * not call it, so a tunnel left connected in a pocket would otherwise be
     * polled every few seconds forever. `ACTION_SCREEN_OFF` closes that gap.
     *
     * The two inputs are kept separately and combined on read, so the "screen
     * off while the activity is resumed" case can never be lost to a
     * read-modify-write race between the main and IO threads.
     */
    private val foreground = java.util.concurrent.atomic.AtomicBoolean(false)
    private val screenOn = java.util.concurrent.atomic.AtomicBoolean(true)

    /** Derived; never assigned directly. */
    private val visible: Boolean get() = foreground.get() && screenOn.get()

    /** Serialises poller start/stop so two triggers cannot leave two loops. */
    private val pollerLock = Any()

    /** Consecutive [VpnReachability.HEALTHY] observations, used for back-off. */
    @Volatile
    private var consecutiveHealthy: Int = 0

    /** Set when the network changed; shortens the handshake grace once. */
    @Volatile
    private var networkChangedAt: Long = 0L

    /** Set while a user-initiated disconnect is in flight (blocks auto-reconnect). */
    @Volatile
    private var userDisconnected: Boolean = false

    init {
        // Restore the last known reachability so the panel is honest right after
        // a cold start, before any new verification has run.
        val record = healthStore?.snapshot()
        if (record != null) {
            _reachability.value = VpnReachability.entries
                .firstOrNull { it.name == record.reachability } ?: VpnReachability.UNKNOWN
            _health.value = record.toSnapshot()
        }
    }

    /** True once the user granted the system VPN consent dialog at least once. */
    fun needsSystemConsent(): Boolean = VpnService.prepare(appContext) != null

    /** Intent to hand to `ActivityResultContracts.StartActivityForResult`. */
    fun consentIntent(): Intent? = VpnService.prepare(appContext)

    /**
     * Connects [profile]. A connect while another tunnel is up replaces it.
     * [auto] marks an automatic reconnect (resume / background check) so the
     * health history can tell it apart from a user tap.
     */
    fun connect(profile: VpnProfile, auto: Boolean = false) {
        val myGeneration = ++generation
        userDisconnected = false
        scope.launch {
            mutex.withLock {
                if (myGeneration != generation) return@withLock
                // An automatic connect is a no-op when another automatic one is
                // already building the same tunnel: the foreground resume and
                // the background worker can both decide to reconnect at the
                // same moment after a process restart.
                val inFlight = _state.value
                if (auto && inFlight is VpnState.Connecting && inFlight.profileId == profile.id) {
                    return@withLock
                }
                // Always tear down first, even when the state says "disconnected":
                // a tunnel can outlive the process (see adoptExistingTunnel), and
                // starting a second one with the same name would fail.
                disconnectInternal()?.let { awaitDown(it) }
                if (myGeneration != generation) return@withLock
                _state.value = VpnState.Connecting(profile.id)
                _reachability.value = VpnReachability.VERIFYING
                val startedAt = System.currentTimeMillis()
                val result = runCatching {
                    bringUpWithRetry(profile)
                    startedAt
                }
                if (myGeneration != generation) {
                    // Superseded while the tunnel was being built: tear down what
                    // this call created so no orphan interface is left behind.
                    runCatching { disconnectInternal() }
                    return@withLock
                }
                result.onFailure { error ->
                    // Include the backend reason: without it a failure is
                    // undiagnosable from a bug report (BackendException carries
                    // the reason as its message).
                    val reason = (error as? BackendException)?.reason?.name
                        ?: error.message?.take(120)
                        ?: error.javaClass.simpleName
                    Log.w(TAG, "VPN connect failed: ${error.javaClass.simpleName} ($reason)")
                    _state.value = VpnState.Failed(profile.id, classify(error))
                    _reachability.value = VpnReachability.UNKNOWN
                    healthStore?.clearSession()
                    runCatching { disconnectInternal() }
                }.onSuccess { connectedAt ->
                    _state.value = VpnState.Connected(profile.id)
                    _health.value = VpnHealthSnapshot(
                        profileId = profile.id,
                        connectedAt = connectedAt,
                        lastHandshakeAt = 0L,
                        checkedAt = connectedAt,
                    )
                    healthStore?.startSession(profile.id, connectedAt)
                    if (auto) healthStore?.recordAutoReconnect(connectedAt)
                    _reachability.value = VpnReachability.VERIFYING
                }
            }
            restartPolling()
        }
    }

    /** Disconnects and returns to [VpnState.Disconnected]. Safe to call repeatedly. */
    fun disconnect() {
        val myGeneration = ++generation
        userDisconnected = true
        scope.launch {
            mutex.withLock {
                if (myGeneration != generation) return@withLock
                val previousProfileId = _state.value.profileId
                _state.value = VpnState.Disconnecting
                val result = runCatching { disconnectInternal() }
                _state.value = if (result.isSuccess) {
                    VpnState.Disconnected
                } else {
                    VpnState.Failed(previousProfileId, VpnFailure.UNKNOWN)
                }
                _reachability.value = VpnReachability.UNKNOWN
                healthStore?.clearSession()
            }
            stopPolling()
        }
    }

    /**
     * Reconnect used by the "reconnect" button: the same profile is brought down
     * and up again, which re-runs the handshake and re-resolves the endpoint.
     */
    fun reconnect() {
        val current = _state.value
        val id = current.profileId ?: return
        val profile = store.find(id)
        if (profile == null) {
            // Publish the failure on the controller's own dispatcher instead of
            // writing the StateFlow from the caller's thread (which may be the
            // main thread): every other transition is published there too.
            val myGeneration = ++generation
            scope.launch {
                mutex.withLock {
                    if (myGeneration != generation) return@withLock
                    _state.value = VpnState.Failed(id, VpnFailure.PROFILE_MISSING)
                }
            }
            return
        }
        connect(profile)
    }

    /** Called when the profile is edited or deleted while it is active. */
    fun onProfileRemoved(id: String) {
        if (_state.value.profileId == id) disconnect()
    }

    /**
     * Restores the tunnel after the system's Always-on VPN feature started the
     * VPN service on its own (boot, connectivity event, app force-stop). The
     * selected profile is re-established; nothing happens when there is no
     * profile to restore. A system-enabled "always on" is authoritative: it
     * does not require the in-app auto-connect switch.
     */
    fun onAlwaysOnTriggered() {
        scope.launch {
            for (delayMs in VpnAlwaysOn.RETRY_DELAYS_MS) {
                if (delayMs > 0) delay(delayMs)
                val selectedId = settings.current().vpnSelectedProfileId
                val profiles = store.snapshot()
                when (
                    VpnAlwaysOn.decide(
                        stateActive = _state.value.isActive,
                        selectedProfileId = selectedId,
                        profiles = profiles,
                        storePersistent = store.isPersistent,
                    )
                ) {
                    VpnAlwaysOn.Action.Connect -> {
                        Log.i(TAG, "always-on VPN: restoring tunnel")
                        val profile = store.find(selectedId)
                        if (profile != null) connect(profile)
                        return@launch
                    }
                    VpnAlwaysOn.Action.Retry -> continue
                    VpnAlwaysOn.Action.Stop -> {
                        Log.i(TAG, "always-on VPN: nothing to restore")
                        return@launch
                    }
                }
            }
            Log.w(TAG, "always-on VPN: profile store never became readable")
        }
    }

    fun onProfileUpdated(profile: VpnProfile) {
        // Apply the new config immediately so an edit takes effect without a
        // surprise "old config still running" state.
        if (_state.value is VpnState.Connected && _state.value.profileId == profile.id) {
            connect(profile)
        }
    }

    /**
     * Re-verifies the tunnel after the app returns to the foreground.
     *
     * This is the "check every time the app is opened" requirement. It never
     * shows a system dialog: when consent is missing it only reports, because
     * an automatic reconnect must not surprise the user with a VPN prompt.
     *
     * @param autoReconnect the user setting (`vpnAutoReconnect`)
     */
    fun onAppForeground(autoReconnect: Boolean) {
        // Going through onVisible() (not a bare `visible = true`) is what makes
        // the poller switch from the background back-off to the fast interval
        // immediately; startPolling() alone would keep the old, long delay.
        // verify = false: the branch below already verifies when connected, and
        // two passes would each pay for a native wgGetConfig.
        onVisible(verify = false)
        scope.launch {
            val current = _state.value
            if (current is VpnState.Connecting || current is VpnState.Disconnecting) {
                startPolling()
                return@launch
            }
            if (current is VpnState.Connected) {
                startPolling()
                val observed = verifyNow()
                maybeAutoReconnect(observed, autoReconnect)
                return@launch
            }
            // Not connected in this process. Two cases:
            //  - the user explicitly disconnected: respect it;
            //  - the process was recreated (or the OS stopped the tunnel): the
            //    tunnel may or may not still exist. Re-adopt it if it does.
            //
            // A background worker may have started the reconnect already; a
            // second connect would only tear its tunnel down again.
            if (userDisconnected) return@launch
            if (_state.value is VpnState.Connecting) {
                startPolling()
                return@launch
            }
            val profileId = healthStore?.snapshot()?.profileId?.takeIf { it.isNotBlank() }
                ?: return@launch
            val profile = store.find(profileId) ?: return@launch
            if (needsSystemConsent() || !autoReconnect) return@launch
            if (adoptExistingTunnel(profile)) {
                startPolling()
                val observed = verifyNow()
                maybeAutoReconnect(observed, autoReconnect)
            } else {
                // No tunnel survived the restart but the user wants it back.
                Log.i(TAG, "VPN session not found after restart; reconnecting")
                connect(profile, auto = true)
            }
        }
    }

    /**
     * Called when the app goes to the background (or the screen turns off).
     *
     * The poller is **not** stopped: a tunnel left connected must still be
     * noticed when it breaks. It drops to the back-off schedule instead, which
     * is what makes "connected in a pocket" cheap.
     */
    fun onAppBackground() {
        foreground.set(false)
        // Restart so the new (slower) interval takes effect immediately instead
        // of after the current foreground delay.
        restartPolling()
    }

    /**
     * The screen turned off. `Activity.onStop()` is **not** called for a screen
     * off, so without this the foreground poll rate would continue indefinitely
     * while the device sits in a pocket.
     */
    fun onScreenOff() {
        screenOn.set(false)
        restartPolling()
    }

    /**
     * The screen turned on again. Verify once right away so the badge is
     * accurate the moment the user looks at it, rather than after a back-off
     * delay.
     */
    fun onScreenOn() {
        screenOn.set(true)
        onVisible()
    }

    /** Marks the app as visible and re-arms the fast poll schedule. */
    private fun onVisible(verify: Boolean = true) {
        val wasVisible = visible
        foreground.set(true)
        restartPolling()
        if (verify && !wasVisible && visible && _state.value is VpnState.Connected) {
            scope.launch { runCatching { verifyNow() } }
        }
    }

    /**
     * The default network changed (Wi-Fi ⇄ mobile, airplane mode, new endpoint).
     *
     * The UDP path is different now, so the existing handshake proves nothing.
     * Verify once with a short grace period and reconnect when the tunnel is not
     * answering — this is what makes the switch-over invisible to the user
     * instead of waiting for the next poll or an app restart.
     */
    fun onNetworkChanged() {
        Log.i(TAG, "network changed; re-verifying (state=${_state.value::class.simpleName})")
        networkChangedAt = System.currentTimeMillis()
        if (_state.value !is VpnState.Connected) {
            // Nothing to re-verify, but a tunnel that the OS dropped while the
            // network was gone should come back.
            if (!userDisconnected && settings.current().vpnAutoReconnect) {
                scope.launch { runCatching { verifyForBackground(settings.current().vpnAutoReconnect) } }
            }
            return
        }
        restartPolling()
        scope.launch {
            val observed = runCatching { verifyNow() }.getOrNull() ?: return@launch
            maybeAutoReconnect(observed, settings.current().vpnAutoReconnect)
        }
    }

    /**
     * One verification pass, usable from WorkManager without touching Compose.
     * Reconnects when the tunnel went stale and the user allows it.
     *
     * WorkManager runs in the same process, but it may be the *first* thing to
     * run after the process was recreated, so `_state` can be
     * [VpnState.Disconnected] while the user still expects a tunnel. In that
     * case the persisted session is used: the tunnel is re-adopted when the
     * library still knows it, and re-established otherwise. Without this the
     * periodic check would return early after every process restart and never
     * do anything.
     *
     * @return the reachability that was observed
     */
    fun verifyForBackground(autoReconnect: Boolean): VpnReachability {
        val current = _state.value
        if (current is VpnState.Connecting || current is VpnState.Disconnecting) {
            // The foreground path (or a previous run of this worker) is already
            // bringing the tunnel up or down; a second connect here would tear
            // down the one it just built.
            return VpnReachability.VERIFYING
        }
        if (current !is VpnState.Connected) {
            if (!autoReconnect || userDisconnected || needsSystemConsent()) return VpnReachability.UNKNOWN
            val profileId = healthStore?.snapshot()?.profileId?.takeIf { it.isNotBlank() }
                ?: return VpnReachability.UNKNOWN
            val profile = store.find(profileId) ?: return VpnReachability.UNKNOWN
            if (!adoptExistingTunnel(profile)) {
                Log.i(TAG, "background check: no tunnel after restart; reconnecting")
                connect(profile, auto = true)
                return VpnReachability.VERIFYING
            }
        }
        val observed = runBlockingVerify()
        val now = System.currentTimeMillis()
        val published = VpnHealthRules.publish(observed, _reachability.value)
        _reachability.value = published
        healthStore?.recordCheck(_health.value.lastHandshakeAt, published, now)
        if (VpnHealthRules.shouldAutoReconnect(
                reachability = observed,
                autoReconnect = autoReconnect,
                consentGranted = !needsSystemConsent(),
                lastAutoReconnectAt = healthStore?.snapshot()?.lastAutoReconnectAt ?: 0L,
                now = now,
            )
        ) {
            val profile = (_state.value as? VpnState.Connected)?.profileId?.let { store.find(it) }
            if (profile != null) {
                Log.i(TAG, "background check: reachability=$observed, reconnecting")
                connect(profile, auto = true)
            }
        }
        return observed
    }

    // ---------------------------------------------------------------- internals

    /**
     * Runs the verification loop while a tunnel is up.
     *
     * The wait between passes comes from [VpnHealthRules.nextPollDelayMs], so the
     * expensive part (`getStatistics` performs a native `wgGetConfig` and parses
     * the whole config) runs at most every 10 s on screen and backs off to 5 min
     * while the app is hidden and the tunnel is healthy.
     */
    private fun startPolling() = synchronized(pollerLock) {
        if (poller?.isActive == true) return@synchronized
        poller = scope.launch {
            while (isActive) {
                val delayMs = VpnHealthRules.nextPollDelayMs(
                    foreground = visible,
                    reachability = _reachability.value,
                    consecutiveHealthy = consecutiveHealthy,
                )
                delay(delayMs)
                if (_state.value !is VpnState.Connected) break
                runCatching { verifyNow() }
            }
        }
    }

    /** Wakes the poller immediately (after a network change or screen-on). */
    private fun restartPolling() = synchronized(pollerLock) {
        poller?.cancel()
        poller = null
        if (_state.value is VpnState.Connected) {
            // startPolling() takes the same lock; it is re-entrant for the same
            // thread, which is what makes this safe.
            startPolling()
        }
    }

    private fun stopPolling() = synchronized(pollerLock) {
        poller?.cancel()
        poller = null
    }

    /**
     * Reads the handshake time of the live tunnel and publishes the result.
     *
     * State is only re-published when something actually changed: assigning an
     * identical value to a `StateFlow` still wakes every collector, and the UI
     * collects both flows, so a blind assignment would re-compose the screen on
     * every pass for no reason.
     */
    private suspend fun verifyNow(): VpnReachability {
        if (BuildConfig.DEBUG) {
            // Debug-only instrumentation: makes the poll rate measurable with
            // `adb logcat -s VpnPoll` instead of inferred from battery stats.
            Log.d(POLL_TAG, "verify visible=$visible reach=${_reachability.value} healthy=$consecutiveHealthy")
        }
        val observed = runBlockingVerify()
        val current = _state.value as? VpnState.Connected ?: return observed
        val now = System.currentTimeMillis()
        val previous = _health.value
        val snapshot = previous.copy(profileId = current.profileId, checkedAt = now)
        consecutiveHealthy = if (observed == VpnReachability.HEALTHY) {
            (consecutiveHealthy + 1).coerceAtMost(VpnHealthRules.MAX_BACKOFF_STEPS)
        } else {
            0
        }
        // Publish when the handshake moved, and also when the "last check" line
        // would otherwise freeze at the connect time; the latter is throttled to
        // once a minute so the UI stays truthful without re-composing every pass.
        val checkedMoved = snapshot.checkedAt - previous.checkedAt >= CHECKED_AT_PUBLISH_MS
        if (snapshot.lastHandshakeAt != previous.lastHandshakeAt || checkedMoved) {
            _health.value = snapshot
        }
        val next = VpnHealthRules.publish(observed, _reachability.value)
        if (_reachability.value != next) {
            _reachability.value = next
        }
        healthStore?.recordCheck(snapshot.lastHandshakeAt, observed, now)
        return observed
    }

    /**
     * Reads the newest handshake time from the backend and checks that the
     * tunnel still exists. Never throws: a backend that is gone simply reports
     * "no handshake". Must run off the main thread.
     *
     * The liveness check matters because the DOWN callback is not guaranteed:
     * when the system revokes the VPN (another VPN app takes over, the user
     * stops it from system settings, the service is killed) the process may
     * never see `onStateChange(DOWN)`. Without this probe the panel would keep
     * showing "connected" for a tunnel that is gone. A tunnel that is no longer
     * running is published as [VpnState.Disconnected] here.
     */
    private fun runBlockingVerify(): VpnReachability {
        val tunnel = activeTunnel
        val engine = backend
        if (tunnel == null || engine == null || tunnel.adopted) {
            // No tunnel handle in this process. That is the normal state right
            // after a process restart, where the OS may still hold a live
            // tunnel. Returning UNKNOWN unconditionally would erase the
            // TUNNEL_ONLY state ten seconds after adopting it and never
            // re-verify, so ask the OS (cheap, no native call) instead.
            if (_state.value !is VpnState.Connected) return VpnReachability.UNKNOWN
            return when (tunnelProbe(appContext)) {
                VpnTunnelProbe.State.ACTIVE -> VpnReachability.TUNNEL_ONLY
                VpnTunnelProbe.State.INACTIVE -> {
                    // The OS no longer has our tunnel: report it honestly.
                    Log.i(TAG, "adopted tunnel is gone from the OS; treating as down")
                    synchronized(this@VpnController) {
                        if (activeTunnel == null || activeTunnel?.adopted == true) {
                            activeTunnel = null
                            _reachability.value = VpnReachability.UNKNOWN
                            healthStore?.clearSession()
                            _state.value = VpnState.Disconnected
                        }
                    }
                    VpnReachability.UNKNOWN
                }
                VpnTunnelProbe.State.UNKNOWN -> VpnReachability.UNKNOWN
            }
        }
        val running = runCatching { engine.runningTunnelNames }.getOrNull()
        if (running != null && tunnel.getName() !in running) {
            Log.i(TAG, "tunnel ${tunnel.getName()} is no longer running; treating as down")
            synchronized(this@VpnController) {
                if (activeTunnel === tunnel) {
                    activeTunnel = null
                    _reachability.value = VpnReachability.UNKNOWN
                    healthStore?.clearSession()
                    _state.value = VpnState.Disconnected
                }
            }
            return VpnReachability.UNKNOWN
        }
        val handshake = runCatching {
            val stats: Statistics = engine.getStatistics(tunnel)
            val times = stats.peers().mapNotNull { key ->
                runCatching { stats.peer(key)?.latestHandshakeEpochMillis() }.getOrNull()
            }
            VpnHealthRules.newestHandshake(times)
        }.onFailure { Log.w(TAG, "statistics unavailable: ${it.javaClass.simpleName}") }
            .getOrDefault(0L)
        val now = System.currentTimeMillis()
        if (handshake > 0L) {
            _health.value = _health.value.copy(lastHandshakeAt = handshake, checkedAt = now)
        }
        val current = _state.value as? VpnState.Connected ?: return VpnReachability.UNKNOWN
        val snapshot = _health.value.copy(profileId = current.profileId, checkedAt = now)
        // Right after a network change the handshake cannot have happened yet,
        // so a stale handshake from the previous path would look like a failure
        // and trigger a pointless reconnect. Handing the network-change moment
        // to classify() re-arms the grace from that point, which is the only way
        // a 10 s override can matter at all (the normal grace is longer).
        return VpnHealthRules.classify(snapshot, now, graceStartOverride = networkChangedAt)
    }

    private fun maybeAutoReconnect(observed: VpnReachability, autoReconnect: Boolean) {
        val current = _state.value as? VpnState.Connected ?: return
        val now = System.currentTimeMillis()
        val allowed = VpnHealthRules.shouldAutoReconnect(
            reachability = observed,
            autoReconnect = autoReconnect,
            consentGranted = !needsSystemConsent(),
            lastAutoReconnectAt = healthStore?.snapshot()?.lastAutoReconnectAt ?: 0L,
            now = now,
        )
        if (!allowed) return
        val profile = current.profileId?.let { store.find(it) } ?: return
        Log.i(TAG, "reachability=$observed on resume; reconnecting")
        connect(profile, auto = true)
    }

    /**
     * Re-attaches to a tunnel that outlived this controller (e.g. the activity
     * was recreated while the VpnService kept running). Returns true when a
     * tunnel with the profile's name is actually running.
     *
     * The WireGuard library keeps its tunnel registry in process memory, so
     * after a *process* death `runningTunnelNames` is empty even though the OS
     * may still hold the interface. That is why the OS is asked next
     * ([VpnTunnelProbe]): when it still owns a VPN network, the tunnel is
     * reported as [VpnReachability.TUNNEL_ONLY] and **not** rebuilt — rebuilding
     * would cost a needless DOWN→UP and a new handshake. Only when the OS also
     * says the tunnel is gone is it re-established.
     */
    private fun adoptExistingTunnel(profile: VpnProfile): Boolean {
        val name = com.yukiorita.halfbrowser.core.VpnNaming.tunnelNameFor(profile.id)
        val engine = backend ?: GoBackend(appContext).also { backend = it }
        val running = runCatching { engine.runningTunnelNames }.getOrDefault(emptySet())
        if (name !in running) {
            // The library's registry is process-local, so a miss here does not
            // prove the tunnel is gone. Ask the OS before tearing anything down:
            // rebuilding a live tunnel costs a needless DOWN→UP and handshake.
            if (tunnelProbe(appContext) == VpnTunnelProbe.State.ACTIVE) {
                Log.i(TAG, "tunnel $name is alive in the OS but not readable here")
                // Register a proxy handle even though statistics cannot be read
                // through it. Without one, disconnectInternal() would have
                // nothing to tear down and a later connect() would try to build
                // a second tunnel with the same name, which fails.
                activeTunnel = ActiveTunnel(profile.id, adopted = true) { tunnelName, up ->
                    onTunnelState(tunnelName, up, profile.id)
                }
                _state.value = VpnState.Connected(profile.id)
                _reachability.value = VpnReachability.TUNNEL_ONLY
                // Keep the recorded session: the tunnel has been up since before
                // this process existed, so overwriting connectedAt with "now"
                // would claim it just started. Only start a session when the
                // record does not already describe this profile.
                val record = healthStore?.snapshot()
                if (record == null || record.profileId != profile.id || record.connectedAt <= 0L) {
                    healthStore?.startSession(profile.id, System.currentTimeMillis())
                }
                return true
            }
            return false
        }
        activeTunnel = ActiveTunnel(profile.id) { tunnelName, up ->
            onTunnelState(tunnelName, up, profile.id)
        }
        _state.value = VpnState.Connected(profile.id)
        _reachability.value = VpnReachability.VERIFYING
        Log.i(TAG, "adopted running tunnel $name")
        return true
    }

    /**
     * Brings the tunnel up, retrying briefly when the backend reports
     * `UNABLE_TO_START_VPN`.
     *
     * That error means the GoBackend `VpnService` was not ready yet — typical
     * right after the process is recreated (the service is started
     * asynchronously) and after a network change. Without the retry an
     * automatic reconnect can fail once and leave the user without a tunnel
     * until the next trigger.
     */
    private suspend fun bringUpWithRetry(profile: VpnProfile) {
        var lastError: Throwable? = null
        for (attempt in 0 until BRING_UP_ATTEMPTS) {
            if (attempt > 0) delay(BRING_UP_RETRY_DELAY_MS * attempt)
            val error = runCatching { bringUp(profile) }.exceptionOrNull()
            if (error == null) return
            lastError = error
            // Only "the service is not ready" is worth retrying. Consent,
            // bad config and TUN failures are deterministic.
            val retryable = (error as? BackendException)?.reason ==
                BackendException.Reason.UNABLE_TO_START_VPN
            if (!retryable) break
            Log.i(TAG, "VPN service not ready (attempt ${attempt + 1}/$BRING_UP_ATTEMPTS); retrying")
        }
        throw lastError ?: IllegalStateException("tunnel did not start")
    }

    private fun bringUp(profile: VpnProfile) {
        val intent = VpnService.prepare(appContext)
        if (intent != null) {
            throw ConsentRequired()
        }
        val config = parseScoped(profile.configText)
        if (BuildConfig.DEBUG) {
            // Proves which keepalive/MTU the tunnel was actually built with, so
            // the power-saving option can be verified rather than assumed.
            val keepalive = config.peers.joinToString(",") {
                it.persistentKeepalive.orElse(-1).toString()
            }
            Log.d(POLL_TAG, "tunnel config: keepalive=[$keepalive] mtu=${config.`interface`.mtu.orElse(-1)}")
        }
        val tunnel = ActiveTunnel(profile.id) { name, up -> onTunnelState(name, up, profile.id) }
        // Publish the tunnel *before* setState: GoBackend can call onStateChange
        // synchronously inside setState, and that callback is ignored unless this
        // tunnel is already the active one. On failure it is cleared again so a
        // failed attempt never leaves a half-registered tunnel behind.
        activeTunnel = tunnel
        val engine = backend ?: GoBackend(appContext).also { backend = it }
        try {
            engine.setState(tunnel, Tunnel.State.UP, config)
        } catch (error: Throwable) {
            if (activeTunnel === tunnel) activeTunnel = null
            throw error
        }
    }

    /**
     * GoBackend may call onStateChange synchronously on the thread that invoked
     * setState() (possibly while this controller holds [mutex]). Updating state
     * directly there would interleave with that locked section, and blocking
     * would deadlock, so the update is published asynchronously and only when
     * this tunnel is still the active one.
     *
     * A DOWN that arrives while this tunnel is still active was *not* requested
     * by us: the system revoked the VPN, the service crashed, or the tunnel was
     * stopped from outside. That case must not be swallowed — the panel would
     * keep claiming "connected" — so the tunnel is released and the state moves
     * to [VpnState.Disconnected].
     */
    private fun onTunnelState(name: String, up: Boolean, profileId: String) {
        scope.launch {
            synchronized(this@VpnController) {
                // Our own teardown (disconnectInternal) clears activeTunnel
                // before calling setState(DOWN), so a DOWN for a tunnel that is
                // no longer active is expected and ignored here.
                if (activeTunnel?.id != profileId) return@synchronized
                _state.value = if (up) {
                    VpnState.Connected(profileId)
                } else {
                    activeTunnel = null
                    _reachability.value = VpnReachability.UNKNOWN
                    healthStore?.clearSession()
                    VpnState.Disconnected
                }
            }
            Log.i(TAG, "tunnel $name ${if (up) "up" else "down"}")
        }
    }

    /**
     * Tears the active tunnel down, if any, and returns a signal completed when
     * the tunnel reports DOWN. The signal is completed by the tunnel's own
     * onStateChange callback, which may fire synchronously inside the
     * `setState(DOWN)` call below — no blocking happens on that thread.
     * Returns null when there was no tunnel to tear down.
     */
    private fun disconnectInternal(): CompletableDeferred<Unit>? {
        val tunnel = activeTunnel
        val engine = backend
        activeTunnel = null
        if (tunnel == null || engine == null) return null
        val down = CompletableDeferred<Unit>()
        tunnel.onDown = { down.complete(Unit) }
        runCatching { engine.setState(tunnel, Tunnel.State.DOWN, null) }
        return down
    }

    /**
     * Waits for [down] up to [TEARDOWN_TIMEOUT_MS], then proceeds regardless.
     * The timeout guarantees the caller (holding [mutex]) can never be wedged
     * by a tunnel whose DOWN callback never arrives.
     */
    private suspend fun awaitDown(down: CompletableDeferred<Unit>) {
        withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { down.await() }
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
     * Parses the profile with the runtime tuning and the app-only scope applied.
     *
     * Order matters: tuning first (keepalive / MTU / DNS), then the scope, then
     * the library parser as the final authority.
     *
     * The allow-list is the primary form. If the library rejects it (a future
     * version could drop `IncludedApplications`), the exclusion form is tried so
     * the feature still works; if both fail the profile is reported as bad
     * rather than silently routing every app.
     */
    private fun parseScoped(configText: String): Config {
        val own = appContext.packageName
        val tuned = VpnTuning.tune(configText, keepaliveSeconds())
        // Primary: allow-list containing only this app.
        val included = runCatching {
            Config.parse(ByteArrayInputStream(
                VpnScope.applyAppOnlyScope(tuned, own).toByteArray(Charsets.UTF_8),
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
                VpnScope.applyScope(tuned, others).toByteArray(Charsets.UTF_8),
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
        VpnScope.applyAppOnlyScope(VpnTuning.tune(configText, keepaliveSeconds()), appContext.packageName)

    /** Keepalive the user's power-saving choice implies. */
    private fun keepaliveSeconds(): Int =
        if (settings.current().vpnPowerSaver) {
            VpnTuning.POWER_SAVER_KEEPALIVE_SECONDS
        } else {
            VpnTuning.DEFAULT_KEEPALIVE_SECONDS
        }

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
        val id: String,
        /**
         * True when this handle only represents a tunnel that already existed in
         * the OS (adopted after a process restart). `getStatistics` cannot read
         * through it, so verification falls back to the OS probe.
         */
        val adopted: Boolean = false,
        private val onState: (String, Boolean) -> Unit,
    ) : Tunnel {
        /**
         * Completed when the tunnel reports DOWN. Assigned by the controller
         * immediately before requesting teardown. Completed from onStateChange
         * without blocking, so a synchronous callback from GoBackend can never
         * deadlock the caller.
         */
        var onDown: (() -> Unit)? = null

        override fun getName(): String = com.yukiorita.halfbrowser.core.VpnNaming.tunnelNameFor(id)

        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN) {
                runCatching { onDown?.invoke() }
            }
            runCatching { onState(getName(), newState == Tunnel.State.UP) }
        }
    }

    companion object {
        private const val TAG = "VpnController"

        /** Debug-only counter tag for the verification loop. */
        const val POLL_TAG = "VpnPoll"
        // Fallback bound for waiting on the DOWN callback. The normal path
        // completes as soon as the tunnel reports DOWN; this only covers a
        // lost callback so reconnect can never hang forever.
        private const val TEARDOWN_TIMEOUT_MS = 1500L

        /** Attempts for a tunnel start that fails because the service is not ready. */
        private const val BRING_UP_ATTEMPTS = 3

        /** Linear back-off between those attempts. */
        private const val BRING_UP_RETRY_DELAY_MS = 400L

        /** How often the "last check" timestamp is published (see verifyNow). */
        private const val CHECKED_AT_PUBLISH_MS = 60_000L

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
