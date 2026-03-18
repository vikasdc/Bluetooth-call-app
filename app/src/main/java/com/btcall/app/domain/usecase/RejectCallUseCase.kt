package com.btcall.app.domain.usecase

import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.model.SignalMessage
import com.btcall.app.domain.repository.BluetoothRepository
import timber.log.Timber
import javax.inject.Inject

class RejectCallUseCase @Inject constructor(
    private val bluetoothRepository: BluetoothRepository
) {
    suspend operator fun invoke(caller: PeerDevice) {
        Timber.d("RejectCallUseCase: rejecting from ${caller.displayName}")
        val reject = SignalMessage(
            type = SignalMessage.MessageType.CALL_REJECT,
            senderId = bluetoothRepository.getLocalDeviceId(),
            senderName = bluetoothRepository.getLocalDeviceName(),
            senderMac = ""
        )
        bluetoothRepository.sendSignal(caller, reject)
    }
}
