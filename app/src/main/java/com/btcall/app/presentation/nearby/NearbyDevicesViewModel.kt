package com.btcall.app.presentation.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btcall.app.domain.model.CallState
import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.repository.BluetoothRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Nearby Devices screen.
 *
 * Exposes:
 * - [uiState]: list of nearby peers + BT enabled flag
 * - [callState]: current call FSM state (via bound service)
 *
 * The ViewModel does NOT directly hold a reference to the service.
 * The Activity binds to [BluetoothCallService] and passes it here via [onServiceConnected].
 */
@HiltViewModel
class NearbyDevicesViewModel @Inject constructor(
    private val bluetoothRepository: BluetoothRepository
) : ViewModel() {

    data class UiState(
        val nearbyDevices: List<PeerDevice> = emptyList(),
        val isBluetoothEnabled: Boolean = true,
        val isScanning: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        observeNearbyDevices()
        observeScanState()
        checkBluetooth()
    }

    private fun observeNearbyDevices() {
        viewModelScope.launch {
            bluetoothRepository.nearbyDevices.collect { devices ->
                _uiState.update { it.copy(nearbyDevices = devices) }
            }
        }
    }

    private fun observeScanState() {
        viewModelScope.launch {
            bluetoothRepository.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }
    }

    fun checkBluetooth() {
        val enabled = bluetoothRepository.isBluetoothEnabled()
        _uiState.update { it.copy(isBluetoothEnabled = enabled) }
    }

    fun refreshDiscovery() {
        viewModelScope.launch {
            bluetoothRepository.startDiscovery()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
