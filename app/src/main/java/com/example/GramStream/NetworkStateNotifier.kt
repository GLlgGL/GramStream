package com.example.GramStream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fired by AddonForegroundService's network callback whenever the default
 * network changes (Wi-Fi <-> cellular handover). Just a change signal -
 * anything showing network-dependent info (Home's manifest link/IP)
 * observes this and re-derives its own state rather than NetworkStateNotifier
 * owning the IP-resolution logic itself.
 */
object NetworkStateNotifier {
    private val _changeCounter = MutableStateFlow(0L)
    val changeCounter = _changeCounter.asStateFlow()

    fun notifyChanged() {
        _changeCounter.value = System.currentTimeMillis()
    }
}