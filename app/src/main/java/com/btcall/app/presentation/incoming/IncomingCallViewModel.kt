package com.btcall.app.presentation.incoming

import androidx.lifecycle.ViewModel
import com.btcall.app.domain.model.PeerDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class IncomingCallViewModel @Inject constructor() : ViewModel() {

    data class UiState(
        val callerPeer: PeerDevice? = null,
        val isDecided: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun setCaller(id: String, name: String, mac: String) {
        _uiState.update {
            it.copy(callerPeer = PeerDevice(id, name, mac, -50))
        }
    }

    fun onDecisionMade() {
        _uiState.update { it.copy(isDecided = true) }
    }
}
