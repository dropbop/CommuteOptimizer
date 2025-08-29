package com.dropbop.commuteoptimizer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dropbop.commuteoptimizer.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder  // <-- add this import

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
            binding.txtStatus.text = getString(R.string.status_permissions_denied)
            showIdle()
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

        // Initial UI state
        showIdle()
        binding.chrono.stop()
        binding.chrono.base = SystemClock.elapsedRealtime()

        // Show actual log path for convenience
        val dir = requireContext()
            .getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?.resolve("commute-logs")
        binding.txtPath.text = getString(
            R.string.logs_path_fmt,
            dir?.path ?: getString(R.string.logs_path_unavailable)
        )

        binding.btnStart.setOnClickListener { ensurePermissionsThenStart() }
        binding.btnStop.setOnClickListener { promptLabelAndStop() }
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
        if (allGranted) startRecording() else requestPerms.launch(need.toTypedArray())
    }

    private fun startRecording() {
        val ctx = requireContext()
        val intent = Intent(ctx, LocationRecordingService::class.java).apply {
            action = LocationRecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(ctx, intent)

        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
        binding.txtStatus.text = getString(R.string.status_recording_ellipsis)

        // Visuals
        showRecording()
        binding.chrono.base = SystemClock.elapsedRealtime()
        binding.chrono.start()
    }

    private fun promptLabelAndStop() {
        val input = AppCompatEditText(requireContext()).apply {
            hint = getString(R.string.route_label_hint_inline)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_label_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val label = input.text?.toString().orEmpty().ifBlank { getString(R.string.unlabeled) }

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
                binding.txtStatus.text = getString(R.string.status_saved_trip, label)

                // Visuals
                binding.chrono.stop()
                showIdle()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRecording() {
        binding.chipStatus.text = getString(R.string.status_recording)
        binding.chipStatus.setChipIconResource(R.drawable.ic_dot_green)
    }

    private fun showIdle() {
        binding.chipStatus.text = getString(R.string.status_idle)
        binding.chipStatus.setChipIconResource(R.drawable.ic_dot_grey)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
