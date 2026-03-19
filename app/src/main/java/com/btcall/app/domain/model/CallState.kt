package com.btcall.app.domain.model

/**
 * Finite state machine for a P2P Bluetooth voice call.
 *
 * State transitions:
 *
 *   IDLE ──[initiate call]──► CALLING ──[remote accepts]──► CONNECTED
 *                │                    ──[remote rejects]──► IDLE
 *                │                    ──[remote busy]────► IDLE
 *                │
 *   IDLE ──[receive request]─► RINGING ──[accept]──────────► CONNECTED
 *                                       ──[reject]──────────► IDLE
 *
 *   CONNECTED ──[hangup / disconnect]──► ENDED ──► IDLE
 */
sealed class CallState {
    /** No active or pending call. App is discoverable. */
    object Idle : CallState()

    /**
     * Local device has sent a CALL_REQUEST and is waiting for remote response.
     * @param remotePeer The callee device.
     */
    data class Calling(val remotePeer: PeerDevice) : CallState()

    /**
     * Local device has received a CALL_REQUEST; ringing UI is shown.
     * @param callerPeer The caller device.
     */
    data class Ringing(val callerPeer: PeerDevice) : CallState()

    /**
     * RFCOMM socket is open; audio streaming is active.
     * @param remotePeer Connected peer.
     * @param isMuted    Whether local mic is muted.
     * @param durationSeconds Call duration updated every second.
     */
    data class Connected(
        val remotePeer: PeerDevice,
        val isMuted: Boolean = false,
        val durationSeconds: Long = 0L,
        val isSpeakerOn: Boolean = true
    ) : CallState()

    /**
     * Call has terminated (normal or due to error).
     * @param reason Human-readable end reason shown briefly in UI.
     */
    data class Ended(val reason: EndReason) : CallState()
}

enum class EndReason {
    LOCAL_HANGUP,
    REMOTE_HANGUP,
    REJECTED,
    BUSY,
    CONNECTION_LOST,
    TIMEOUT,
    BLUETOOTH_OFF
}
