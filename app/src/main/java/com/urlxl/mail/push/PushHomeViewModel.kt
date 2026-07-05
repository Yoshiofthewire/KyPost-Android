package com.urlxl.mail.push

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PushHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = PushRuntime.graph(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val isWorking = MutableStateFlow(false)
    private val localMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PushHomeUiState> = combine(
        graph.repository.state,
        isWorking,
        localMessage,
    ) { repo, working, local ->
        PushHomeUiState(
            pairing = repo.pairing,
            lastTokenSyncAtEpochMs = repo.lastTokenSyncAtEpochMs,
            syncError = repo.syncError,
            latestPayload = repo.latestPayload,
            history = repo.history,
            deliveryMode = repo.deliveryMode,
            isWorking = working,
            localMessage = local,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PushHomeUiState(),
    )

    init {
        scope.launch {
            val state = graph.repository.state.first()
            if (state.pairing != null) {
                graph.syncCoordinator.syncCurrentPairingToken()
                // Re-read delivery mode & drain any queued pull notifications on open.
                graph.pullCoordinator.pullNowAsync()
            }
        }
    }

    fun consumeLocalMessage() {
        localMessage.value = null
    }

    fun pairFromLink(link: String) {
        scope.launch {
            isWorking.value = true
            val parsed = NativePairingDeepLinkParser.parse(link)
            when (parsed) {
                is PairingParseResult.Error -> {
                    localMessage.value = parsed.reason
                    isWorking.value = false
                }
                is PairingParseResult.Success -> {
                    val resolution = NativeRegistrationEndpointResolver.resolve(
                        qrReg = parsed.pairing.registrationUrl.takeIf { it.isNotBlank() },
                        qrServerUrl = parsed.pairing.serverUrl,
                    )
                    when (resolution) {
                        is NativeRegistrationEndpointResolver.Resolution.MissingServerUrl -> {
                            localMessage.value = "Pairing QR is missing a server URL"
                            isWorking.value = false
                        }
                        is NativeRegistrationEndpointResolver.Resolution.Resolved -> {
                            val pending = parsed.pairing.copy(registrationUrl = resolution.registrationUrl)
                            val result = graph.syncCoordinator.attemptPairing(pending)
                            if (result is NativeRegistrationResult.Success) {
                                // If the server put this user in pull mode, start fetching immediately.
                                graph.pullCoordinator.pullNowAsync()
                            }
                            localMessage.value = when (result) {
                                is NativeRegistrationResult.Success -> "Device paired and token synced"
                                is NativeRegistrationResult.Error -> {
                                    val suffix = if (result.expiredPairingToken) " — rescan the pairing QR code" else ""
                                    "Pairing failed: ${result.message}$suffix"
                                }
                            }
                            isWorking.value = false
                        }
                    }
                }
            }
        }
    }

    fun clearPairing() {
        scope.launch {
            isWorking.value = true
            graph.repository.clearPairing()
            localMessage.value = "Local pairing cleared"
            isWorking.value = false
        }
    }

    fun resyncToken() {
        scope.launch {
            isWorking.value = true
            val result = graph.syncCoordinator.syncCurrentPairingToken()
            if (result is NativeRegistrationResult.Success) {
                graph.pullCoordinator.pullNowAsync()
            }
            localMessage.value = when (result) {
                is NativeRegistrationResult.Success -> "Token synced"
                is NativeRegistrationResult.Error -> {
                    val suffix = if (result.expiredPairingToken) " — rescan the pairing QR code" else ""
                    "Token sync failed: ${result.message}$suffix"
                }
            }
            isWorking.value = false
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}

data class PushHomeUiState(
    val pairing: PairingData? = null,
    val lastTokenSyncAtEpochMs: Long? = null,
    val syncError: String? = null,
    val latestPayload: PushPayload? = null,
    val history: List<PushPayload> = emptyList(),
    val deliveryMode: DeliveryMode = DeliveryMode.PUSH,
    val isWorking: Boolean = false,
    val localMessage: String? = null,
)
