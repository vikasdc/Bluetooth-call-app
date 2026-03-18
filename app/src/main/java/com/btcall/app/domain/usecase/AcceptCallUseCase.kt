package com.btcall.app.domain.usecase

import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.model.SignalMessage
import com.btcall.app.domain.repository.BluetoothRepository
import timber.log.Timber
import javax.inject.Inject

/**
 * Accepts an incoming call request.
 *
 * Flow:
 * 1. Send CALL_ACCEPT to caller via BLE GATT
 * 2. Open RFCOMM server socket and wait for caller to connect
 */
class AcceptCallUseCase @Inject constructor(
    private val bluetoothRepository: BluetoothRepository
) {
    sealed class Result {
        object Connected : Result()
        data class Error(val message: String) : Result()
    }

    suspend operator fun invoke(caller: PeerDevice): Result {
        Timber.d("AcceptCallUseCase: accepting from ${caller.displayName}")

        val accept = SignalMessage(
            type = SignalMessage.MessageType.CALL_ACCEPT,
            senderId = bluetoothRepository.getLocalDeviceId(),
            senderName = bluetoothRepository.getLocalDeviceName(),
            senderMac = bluetoothRepository.getLocalMacAddress()
        )

        bluetoothRepository.sendSignal(caller, accept).onFailure { err ->
            Timber.e(err, "Failed to send CALL_ACCEPT")
            return Result.Error(err.message ?: "Failed to send accept")
        }

        // Accept incoming RFCOMM connection
        bluetoothRepository.acceptRfcomm().onFailure { err ->
            Timber.e(err, "RFCOMM accept failed")
            return Result.Error(err.message ?: "Connection setup failed")
        }

        return Result.Connected
    }
}
