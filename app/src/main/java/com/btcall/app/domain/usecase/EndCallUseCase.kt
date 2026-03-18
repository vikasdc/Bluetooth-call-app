package com.btcall.app.domain.usecase

import com.btcall.app.domain.model.CallDirection
import com.btcall.app.domain.model.CallRecord
import com.btcall.app.domain.model.EndReason
import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.model.SignalMessage
import com.btcall.app.domain.repository.AudioRepository
import com.btcall.app.domain.repository.BluetoothRepository
import com.btcall.app.domain.repository.CallHistoryRepository
import timber.log.Timber
import javax.inject.Inject

class EndCallUseCase @Inject constructor(
    private val bluetoothRepository: BluetoothRepository,
    private val audioRepository: AudioRepository,
    private val callHistoryRepository: CallHistoryRepository
) {
    suspend operator fun invoke(
        remotePeer: PeerDevice,
        direction: CallDirection,
        startTimestampMs: Long,
        endReason: EndReason = EndReason.LOCAL_HANGUP,
        sendSignal: Boolean = true
    ) {
        Timber.d("EndCallUseCase: ending call, reason=$endReason")

        if (sendSignal) {
            val endMsg = SignalMessage(
                type = SignalMessage.MessageType.CALL_END,
                senderId = bluetoothRepository.getLocalDeviceId(),
                senderName = "",
                senderMac = ""
            )
            bluetoothRepository.sendSignal(remotePeer, endMsg)
        }

        // Stop audio pipeline
        audioRepository.stopCapture()
        audioRepository.stopPlayback()

        // Close RFCOMM
        bluetoothRepository.disconnectRfcomm()

        // Persist call record
        val endTs = System.currentTimeMillis()
        val duration = (endTs - startTimestampMs) / 1000L
        callHistoryRepository.insertCallRecord(
            CallRecord(
                remotePeerId = remotePeer.deviceId,
                remotePeerName = remotePeer.displayName,
                direction = direction,
                startTimestampMs = startTimestampMs,
                endTimestampMs = endTs,
                durationSeconds = duration,
                endReason = endReason
            )
        )
    }
}
