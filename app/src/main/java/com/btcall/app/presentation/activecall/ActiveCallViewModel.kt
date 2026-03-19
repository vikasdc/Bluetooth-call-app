package com.btcall.app.presentation.activecall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btcall.app.domain.model.CallState
import com.btcall.app.domain.model.PeerDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Active Call screen.
 * Delegates actions to the bound [BluetoothCallService].
 */
@HiltViewModel
class ActiveCallViewModel @Inject constructor() : ViewModel() {

    data class UiState(
        val remotePeer: PeerDevice? = null,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = true,
        val durationSeconds: Long = 0L,
        val connectionQuality: ConnectionQuality = ConnectionQuality.GOOD,
        val isEnded: Boolean = false
    )

    enum class ConnectionQuality { EXCELLENT, GOOD, FAIR, POOR }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun updateFromCallState(state: CallState) {
        when (state) {
            is CallState.Connected -> {
                _uiState.update {
                    it.copy(
                        remotePeer = state.remotePeer,
                        isMuted = state.isMuted,
                        isSpeakerOn = state.isSpeakerOn,
                        durationSeconds = state.durationSeconds,
                        isEnded = false
                    )
                }
            }
            is CallState.Ended -> _uiState.update { it.copy(isEnded = true) }
            else -> {}
        }
    }

    fun formatDuration(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }
}
