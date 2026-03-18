package com.btcall.app.presentation.activecall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.btcall.app.R
import com.btcall.app.databinding.FragmentActiveCallBinding
import com.btcall.app.domain.model.CallState
import com.btcall.app.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Screen 3: Active Call
 *
 * Shown once the RFCOMM connection is established and audio is flowing.
 * Displays:
 * - Remote peer name
 * - Call duration (updated every second)
 * - Mute toggle
 * - End call button
 * - Connection quality indicator
 */
@AndroidEntryPoint
class ActiveCallFragment : Fragment() {

    private var _binding: FragmentActiveCallBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ActiveCallViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentActiveCallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupButtons()
        observeUiState()
        observeCallState()
    }

    private fun setupButtons() {
        binding.btnEndCall.setOnClickListener {
            (requireActivity() as? MainActivity)?.callService?.endCall()
        }

        binding.btnMute.setOnClickListener {
            val currentMuted = viewModel.uiState.value.isMuted
            (requireActivity() as? MainActivity)?.callService?.setMuted(!currentMuted)
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvRemotePeerName.text = state.remotePeer?.displayName ?: "..."
                    binding.tvCallDuration.text = viewModel.formatDuration(state.durationSeconds)

                    // Mute button icon update
                    binding.btnMute.setImageResource(
                        if (state.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on
                    )

                    binding.tvMuteLabel.text = if (state.isMuted) "Unmute" else "Mute"
                }
            }
        }
    }

    private fun observeCallState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val service = (requireActivity() as? MainActivity)?.callService
                    ?: return@repeatOnLifecycle

                service.callState.collect { state ->
                    viewModel.updateFromCallState(state)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
