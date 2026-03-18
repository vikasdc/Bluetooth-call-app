package com.btcall.app.presentation.nearby

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.btcall.app.R
import com.btcall.app.domain.model.PeerDevice

/**
 * RecyclerView adapter for the nearby devices list.
 * Uses ListAdapter (DiffUtil) for efficient updates.
 */
class PeerDeviceAdapter(
    private val onCallClick: (PeerDevice) -> Unit
) : ListAdapter<PeerDevice, PeerDeviceAdapter.ViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<PeerDevice>() {
        override fun areItemsTheSame(old: PeerDevice, new: PeerDevice) =
            old.deviceId == new.deviceId
        override fun areContentsTheSame(old: PeerDevice, new: PeerDevice) = old == new
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_peer_name)
        val tvRssi: TextView = view.findViewById(R.id.tv_peer_rssi)
        val tvStatus: TextView = view.findViewById(R.id.tv_peer_status)
        val btnCall: Button = view.findViewById(R.id.btn_call)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val peer = getItem(position)

        holder.tvName.text = peer.displayName
        holder.tvRssi.text = signalBars(peer.rssi)
        holder.tvStatus.text = if (peer.isAvailable) "Available" else "Busy"
        holder.tvStatus.setTextColor(
            holder.itemView.context.getColor(
                if (peer.isAvailable) R.color.status_available else R.color.status_busy
            )
        )

        holder.btnCall.isEnabled = peer.isAvailable
        holder.btnCall.setOnClickListener { onCallClick(peer) }
    }

    /** Convert RSSI dBm to a human-readable signal indicator */
    private fun signalBars(rssi: Int): String = when {
        rssi >= -60 -> "████ Excellent"
        rssi >= -70 -> "███░ Good"
        rssi >= -80 -> "██░░ Fair"
        else        -> "█░░░ Weak"
    }
}
