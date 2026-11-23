/*
 * MapsFragment.kt
 * Implements the MapsFragment fragment
 * A MapsFragment manages the offline map of Trackbook: it shows the download state and
 * progress and offers download / resume / delete actions.
 *
 * This file is part of
 * TRACKBOOK - Movement Recorder for Android
 *
 * Copyright (c) 2016-22 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 *
 * Trackbook uses osmdroid - OpenStreetMap-Tools for Android
 * https://github.com/osmdroid/osmdroid
 */


package org.y20k.trackbook

import YesNoDialog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.y20k.trackbook.databinding.FragmentMapsBinding
import org.y20k.trackbook.helpers.LogHelper
import org.y20k.trackbook.helpers.OfflineMapHelper
import java.util.concurrent.TimeUnit


/*
 * MapsFragment class
 */
class MapsFragment : Fragment(), YesNoDialog.YesNoDialogListener {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(MapsFragment::class.java)


    /* Main class variables */
    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!
    private var lastKnownWorkState: WorkInfo.State? = null


    /* Overrides onCreateView from Fragment */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // bind layout views
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        // set up button actions
        binding.downloadButton.setOnClickListener { handleDownloadButton() }
        binding.deleteButton.setOnClickListener {
            YesNoDialog(this as YesNoDialog.YesNoDialogListener).show(context = activity as android.content.Context, type = Keys.DIALOG_DELETE_OFFLINE_MAP, message = R.string.offline_map_delete_confirmation_message, yesButton = R.string.offline_map_delete_confirmation_yes)
        }
        binding.importBundledButton.setOnClickListener { handleBundledMapImport() }
        // observe download progress
        observeDownloadWork()
        // fill the ui with the current state
        updateUi()
        return binding.root
    }


    /* Overrides onResume from Fragment */
    override fun onResume() {
        super.onResume()
        updateUi()
    }


    /* Overrides onDestroyView from Fragment */
    override fun onDestroyView() {
        super.onDestroyView()
        // observers are removed automatically with viewLifecycleOwner
        _binding = null
    }


    /* Overrides onYesNoDialog from YesNoDialogListener */
    override fun onYesNoDialog(type: Int, dialogResult: Boolean, payload: Int, payloadString: String) {
        super.onYesNoDialog(type, dialogResult, payload, payloadString)
        when (type) {
            Keys.DIALOG_DELETE_OFFLINE_MAP -> {
                when (dialogResult) {
                    true -> deleteOfflineMap()
                }
            }
        }
    }


    /* Starts or resumes the offline map download via WorkManager */
    private fun startDownload() {
        val workManager = WorkManager.getInstance(requireContext())
        val downloadRequest = OneTimeWorkRequestBuilder<MapDownloadWorker>()
            .setInputData(
                workDataOf(
                    Keys.ARG_OFFLINE_MAP_URL to Keys.OFFLINE_MAP_DOWNLOAD_URL,
                    Keys.ARG_OFFLINE_MAP_FILE_NAME to Keys.OFFLINE_MAP_MAPFORGE_FILE_NAME
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
            .build()
        // KEEP: an existing (running) download is not started twice
        workManager.enqueueUniqueWork(Keys.OFFLINE_MAP_DOWNLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, downloadRequest)
        LogHelper.i(TAG, "Offline map download enqueued.")
    }


    /* Handles the download button - requests the notification permission on Android 13+ if needed */
    private fun handleDownloadButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
            // ask for the notification permission first - the download continues afterwards via the observer
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startDownload()
        }
    }


    /* Deletes the installed offline map - cancels a running download first */
    private fun deleteOfflineMap() {
        val workManager = WorkManager.getInstance(requireContext())
        workManager.cancelUniqueWork(Keys.OFFLINE_MAP_DOWNLOAD_WORK_NAME)
        lifecycleScope.launch {
            val success: Boolean = withContext(Dispatchers.IO) {
                OfflineMapHelper.deleteOfflineMaps(requireContext())
            }
            LogHelper.i(TAG, "Offline map deleted: $success")
            updateUi()
        }
    }


    /* Imports the map file bundled in assets (debug builds) */
    private fun handleBundledMapImport() {
        lifecycleScope.launch {
            val success: Boolean = withContext(Dispatchers.IO) {
                OfflineMapHelper.installBundledMapsforgeMap(requireContext())
            }
            LogHelper.i(TAG, "Bundled offline map import: $success")
            updateUi()
        }
    }


    /* Observes the download work and updates the ui accordingly */
    private fun observeDownloadWork() {
        val newObserver = Observer<List<WorkInfo>> { workInfoList ->
            val workInfo = workInfoList.firstOrNull()
            lastKnownWorkState = workInfo?.state
            when (workInfo?.state) {
                WorkInfo.State.RUNNING -> showDownloading(workInfo.progress.getInt(Keys.PROGRESS_OFFLINE_MAP_PERCENT, 0))
                WorkInfo.State.ENQUEUED -> showDownloading(0)
                else -> updateUi()
            }
        }
        WorkManager.getInstance(requireContext()).getWorkInfosForUniqueWorkLiveData(Keys.OFFLINE_MAP_DOWNLOAD_WORK_NAME).observe(viewLifecycleOwner, newObserver)
    }


    /* Switches the ui to the "downloading" state */
    private fun showDownloading(percent: Int) {
        binding.statusText.text = if (percent > 0) getString(R.string.offline_map_status_downloading, percent) else getString(R.string.offline_map_status_preparing)
        binding.downloadButton.isVisible = true
        binding.downloadButton.text = getString(R.string.offline_map_button_pause)
        binding.deleteButton.isVisible = false
        binding.importBundledButton.isVisible = false
        binding.progressBar.isVisible = true
        binding.progressBar.progress = percent.coerceIn(0, 100)
    }


    /* Updates the ui according to the current offline map state */
    private fun updateUi() {
        val context = context ?: return
        val installed: Boolean = OfflineMapHelper.isOfflineMapInstalled(context)
        val partialDownload: Boolean = OfflineMapHelper.getMapsforgePartFile(context).exists()
        val workRunning: Boolean = lastKnownWorkState == WorkInfo.State.RUNNING || lastKnownWorkState == WorkInfo.State.ENQUEUED
        if (workRunning) return  // the download observer handles the ui while a download runs

        binding.progressBar.isVisible = false
        if (installed) {
            // CASE: offline map installed
            val size: String = Formatter.formatShortFileSize(context, OfflineMapHelper.getInstalledMapSize(context))
            binding.statusText.text = getString(R.string.offline_map_status_installed, size)
            binding.downloadButton.isVisible = false
            binding.deleteButton.isVisible = true
            binding.importBundledButton.isVisible = false
        } else {
            // CASE: no offline map installed
            if (partialDownload) {
                binding.statusText.text = getString(R.string.offline_map_status_paused)
                binding.downloadButton.text = getString(R.string.offline_map_button_resume)
            } else {
                binding.statusText.text = getString(R.string.offline_map_status_not_downloaded)
                binding.downloadButton.text = getString(R.string.offline_map_button_download)
            }
            binding.downloadButton.isVisible = true
            binding.deleteButton.isVisible = false
            // offer the bundled map only on debug builds (see docs/OFFLINE_MAP.md)
            binding.importBundledButton.isVisible = BuildConfig.DEBUG
        }
        binding.mapViewButton.isVisible = installed
        binding.mapViewButton.setOnClickListener {
            findNavController().navigate(R.id.action_offline_maps_fragment_to_map_fragment)
        }
        // show file location and data source
        binding.pathText.text = OfflineMapHelper.getMapsforgeFile(context).absolutePath
    }


    /* Registers the permission launcher for the notification permission (Android 13+) */
    private val requestNotificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            LogHelper.i(TAG, "Notification permission granted.")
        } else {
            LogHelper.i(TAG, "Notification permission NOT granted - download continues without progress notifications.")
        }
        // start the download regardless of the notification permission
        startDownload()
    }

}
