package com.btcall.app.domain.usecase

import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.model.SignalMessage
import com.btcall.app.domain.repository.BluetoothRepository
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Initiates an outgoing call to a peer device.
 *
 * Flow:
 * 1. Send CALL_REQUEST to target via BLE GATT
 * 2. Wait up to 30s for CALL_ACCEPT / CALL_REJECT / CALL_BUSY
 * 3. If accepted → connect RFCOMM socket
 */
class InitiateCallUseCase @Inject constructor(
    private val bluetoothRepository: BluetoothRepository
) {

    sealed class Result {
        data class Accepted(val peer: PeerDevice) : Result()
        data class Rejected(val peer: PeerDevice) : Result()
        data class Busy(val peer: PeerDevice) : Result()
        data class Timeout(val peer: PeerDevice) : Result()
        data class Error(val message: String) : Result()
    }

    suspend operator fun invoke(target: PeerDevice): Result {
        Timber.d("InitiateCallUseCase: calling ${target.displayName}")

        val callRequest = SignalMessage(
            type = SignalMessage.MessageType.CALL_REQUEST,
            senderId = bluetoothRepository.getLocalDeviceId(),
            senderName = bluetoothRepository.getLocalDeviceName(),
            senderMac = bluetoothRepository.getLocalMacAddress()
        )

        // Send the call request
        bluetoothRepository.sendSignal(target, callRequest).onFailure { err ->
            Timber.e(err, "Failed to send CALL_REQUEST")
            return Result.Error(err.message ?: "Failed to send call request")
        }

        // Wait for response with 30s timeout
        val response = withTimeoutOrNull(30_000L) {
            bluetoothRepository.incomingSignals.collect { msg ->
                if (msg.senderId == target.deviceId) {
                    return@collect
                }
            }
        }

        return Result.Timeout(target)  // Will be overridden by call state machine
    }
}
