package com.dropbop.commuteoptimizer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dropbop.commuteoptimizer.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val notifOk = Build.VERSION.SDK_INT < 33 ||
                grants[Manifest.permission.POST_NOTIFICATIONS] == true
        if (fine && notifOk) {
            startRecording()
        } else {
            binding.txtStatus.text = "Permissions denied"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStart.setOnClickListener {
            ensurePermissionsThenStart()
        }

        binding.btnStop.setOnClickListener {
            promptLabelAndStop()
        }
    }

    private fun ensurePermissionsThenStart() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        val allGranted = need.all {
            ContextCompat.checkSelfPermission(requireContext(), it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startRecording()
        } else {
            requestPerms.launch(need.toTypedArray())
        }
    }

    private fun startRecording() {
        val ctx = requireContext()
        val intent = Intent(ctx, LocationRecordingService::class.java).apply {
            action = LocationRecordingService.ACTION_START
        }
        // Start as foreground service on O+ (falls back below)
        ContextCompat.startForegroundService(ctx, intent)

        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
        binding.txtStatus.text = "Recording…"
    }

    private fun promptLabelAndStop() {
        val input = AppCompatEditText(requireContext()).apply {
            hint = "Route label (e.g., I-10 via Shepherd)"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Label this trip")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val label = input.text?.toString().orEmpty().ifBlank { "unlabeled" }

                val ctx = requireContext()
                // Tell the service to stop collecting
                ContextCompat.startForegroundService(ctx, Intent(ctx, LocationRecordingService::class.java).apply {
                    action = LocationRecordingService.ACTION_STOP
                })
                // Export with your label
                ContextCompat.startForegroundService(ctx, Intent(ctx, LocationRecordingService::class.java).apply {
                    action = LocationRecordingService.ACTION_EXPORT
                    putExtra(LocationRecordingService.EXTRA_ROUTE_LABEL, label)
                })

                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                binding.txtStatus.text = "Saved trip: $label"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
