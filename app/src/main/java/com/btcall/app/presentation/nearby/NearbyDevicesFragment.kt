package com.btcall.app.presentation.nearby

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.btcall.app.databinding.FragmentNearbyDevicesBinding
import com.btcall.app.domain.model.CallState
import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Screen 1: Nearby Devices
 *
 * Displays a live list of nearby peers discovered via BLE.
 * User taps "Call" on a peer to initiate a voice call.
 * Shows a visual indicator when a call is in CALLING state.
 */
@AndroidEntryPoint
class NearbyDevicesFragment : Fragment() {

    private var _binding: FragmentNearbyDevicesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NearbyDevicesViewModel by viewModels()
    private lateinit var adapter: PeerDeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNearbyDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSwipeRefresh()
        observeUiState()
        observeCallState()
    }

    private fun setupRecyclerView() {
        adapter = PeerDeviceAdapter { peer -> onCallPressed(peer) }
        binding.rvNearbyDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@NearbyDevicesFragment.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshDiscovery()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.nearbyDevices)

                    binding.tvEmptyState.visibility =
                        if (state.nearbyDevices.isEmpty()) View.VISIBLE else View.GONE

                    binding.scanIndicator.visibility =
                        if (state.isScanning) View.VISIBLE else View.GONE

                    if (!state.isBluetoothEnabled) {
                        binding.tvEmptyState.text = "Bluetooth is off.\nPlease enable Bluetooth."
                        binding.tvEmptyState.visibility = View.VISIBLE
                        (requireActivity() as? MainActivity)?.requestEnableBluetooth()
                    }
                }
            }
        }
    }

    private fun observeCallState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val service = (requireActivity() as? MainActivity)?.callService ?: return@repeatOnLifecycle
                service.callState.collect { state ->
                    updateCallStateUi(state)
                }
            }
        }
    }

    private fun updateCallStateUi(state: CallState) {
        when (state) {
            is CallState.Calling -> {
                binding.tvCallStatus.visibility = View.VISIBLE
                binding.tvCallStatus.text = "Calling ${state.remotePeer.displayName}..."
            }
            is CallState.Ended -> {
                binding.tvCallStatus.visibility = View.VISIBLE
                binding.tvCallStatus.text = state.reason.name.replace("_", " ")
            }
            is CallState.Idle -> {
                binding.tvCallStatus.visibility = View.GONE
            }
            else -> {}
        }
    }

    private fun onCallPressed(peer: PeerDevice) {
        Timber.d("Call pressed for ${peer.displayName}")
        val service = (requireActivity() as? MainActivity)?.callService
        if (service == null) {
            Timber.w("Service not bound yet")
            return
        }
        service.initiateCall(peer)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
