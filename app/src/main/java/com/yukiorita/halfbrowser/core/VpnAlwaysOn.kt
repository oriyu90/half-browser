package com.yukiorita.halfbrowser.core

/**
 * Decision table for restoring the tunnel when the system's Always-on VPN
 * feature starts [com.wireguard.android.backend.GoBackend.VpnService] without
 * going through the app UI (device boot, connectivity events, app force-stop).
 *
 * Why this exists
 * ---------------
 * The WireGuard library exposes a static `GoBackend.setAlwaysOnCallback` that
 * is invoked exactly in that case. If no callback is registered, the system
 * believes the VPN is "always on" while the tunnel is in fact never brought
 * up: with lockdown ("Block connections without VPN") enabled this takes the
 * whole device offline after a reboot, and without lockdown it silently
 * fakes protection. Both are unacceptable, so the callback restores the
 * profile selected in Settings.
 *
 * Pure and I/O-free so the rules are unit-tested; [com.yukiorita.halfbrowser.data.VpnController]
 * supplies the live inputs and performs the actual connect.
 */
object VpnAlwaysOn {

    /**
     * Backoff schedule for profile-store reads. The profile store is
     * Keystore-encrypted and can be unreadable when the service is started
     * before first unlock; the system keeps the service alive, so waiting and
     * re-reading is enough. Roughly 2 minutes of budget, then give up — the
     * next app open re-applies the in-app auto-connect.
     */
    val RETRY_DELAYS_MS = longArrayOf(0L, 5_000L, 15_000L, 30_000L, 60_000L)

    sealed interface Action {
        /** Restore the tunnel for the selected profile right now. */
        data object Connect : Action

        /** The profile store is not readable yet; wait and re-evaluate. */
        data object Retry : Action

        /** Nothing to do, and no point retrying. */
        data object Stop : Action
    }

    /**
     * @param stateActive whether the controller is already connected/connecting
     * @param selectedProfileId the profile selected in Settings ("" when none)
     * @param profiles profiles currently visible in the store (empty when the
     *   store could not be read at all)
     * @param storePersistent false when the encrypted store itself is
     *   unavailable (Keystore failure or pre-unlock)
     */
    fun decide(
        stateActive: Boolean,
        selectedProfileId: String,
        profiles: List<VpnProfile>,
        storePersistent: Boolean,
    ): Action = when {
        stateActive -> Action.Stop
        selectedProfileId.isBlank() -> Action.Stop
        profiles.any { it.id == selectedProfileId } -> Action.Connect
        // The store could not be read at all (empty + non-persistent): the
        // selected id may still exist on disk once the Keystore unlocks.
        profiles.isEmpty() && !storePersistent -> Action.Retry
        else -> Action.Stop
    }
}
