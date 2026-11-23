/*
 * MapDownloadWorker.kt
 * Implements the MapDownloadWorker class
 * A MapDownloadWorker downloads the offline map file (jiangsu.map) into the app's private
 * external storage. It supports resuming interrupted downloads (HTTP range requests),
 * automatic retries with exponential backoff and progress reporting via WorkManager.
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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.y20k.trackbook.helpers.LogHelper
import org.y20k.trackbook.helpers.OfflineMapHelper
import org.y20k.trackbook.helpers.PreferencesHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL


/*
 * MapDownloadWorker class
 */
class MapDownloadWorker(context: Context, workerParameters: WorkerParameters) : CoroutineWorker(context, workerParameters) {


    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(MapDownloadWorker::class.java)


    /* Main class variables */
    private var lastNotificationUpdate: Long = 0L


    /* Overrides doWork from CoroutineWorker */
    override suspend fun doWork(): Result {
        val downloadUrl: String = inputData.getString(Keys.ARG_OFFLINE_MAP_URL) ?: Keys.OFFLINE_MAP_DOWNLOAD_URL
        val targetFile: File = OfflineMapHelper.getMapsforgeFile(applicationContext)
        val partFile: File = OfflineMapHelper.getMapsforgePartFile(applicationContext)
        LogHelper.i(TAG, "Starting offline map download: $downloadUrl")

        // run in the foreground to survive longer downloads while the app is in the background
        try {
            setForeground(createForegroundInfo(0L, -1L))
        } catch (e: Exception) {
            // foreground is optional - continue without it
            LogHelper.d(TAG, "Unable to start foreground download. $e")
        }

        return try {
            val success: Boolean = withContext(Dispatchers.IO) {
                downloadMapFile(downloadUrl, partFile)
            }
            if (success) {
                // move the downloaded file into place and verify it
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    LogHelper.e(TAG, "Unable to move downloaded map file into place.")
                    return Result.failure()
                }
                if (!OfflineMapHelper.isValidMapsforgeFile(targetFile)) {
                    LogHelper.e(TAG, "Downloaded map file is invalid - deleting.")
                    targetFile.delete()
                    showResultNotification(success = false)
                    return Result.failure()
                }
                // store the update timestamp - map fragments listen for this change
                PreferencesHelper.saveOfflineMapUpdatedAt()
                showResultNotification(success = true)
                LogHelper.i(TAG, "Offline map download finished: ${targetFile.absolutePath}")
                Result.success(workDataOf(Keys.PROGRESS_OFFLINE_MAP_PERCENT to 100))
            } else {
                // download was cancelled - keep the partial file for resuming
                Result.retry()
            }
        } catch (e: IOException) {
            LogHelper.e(TAG, "Offline map download failed. $e")
            if (runAttemptCount < Keys.OFFLINE_MAP_DOWNLOAD_MAX_RETRIES) {
                // retry later with exponential backoff - the partial file is kept for resuming
                Result.retry()
            } else {
                showResultNotification(success = false)
                Result.failure()
            }
        }
    }


    /* Downloads the map file with resume support - returns false if cancelled, throws on network errors */
    @Throws(IOException::class)
    private suspend fun downloadMapFile(downloadUrl: String, partFile: File): Boolean {
        val alreadyDownloaded: Long = if (partFile.exists()) partFile.length() else 0L
        val url = URL(downloadUrl)
        val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.instanceFollowRedirects = true
        if (alreadyDownloaded > 0L) {
            // request the remaining bytes - enables resuming after an interrupted download
            connection.setRequestProperty("Range", "bytes=$alreadyDownloaded-")
        }
        val responseCode: Int = connection.responseCode
        var resumeOffset: Long = alreadyDownloaded
        when {
            responseCode == HTTP_PARTIAL_CONTENT -> {
                // CASE: server supports resuming - append to the partial file
                LogHelper.i(TAG, "Resuming download at byte $alreadyDownloaded")
            }
            responseCode == HTTP_OK && alreadyDownloaded > 0L -> {
                // CASE: server ignored the range request - restart the download
                LogHelper.i(TAG, "Server does not support resuming - restarting download.")
                resumeOffset = 0L
            }
            responseCode !in HTTP_SUCCESS_RANGE -> {
                connection.disconnect()
                throw IOException("Unexpected HTTP response code: $responseCode")
            }
        }

        val contentLength: Long = connection.contentLengthLong
        val totalBytes: Long = if (contentLength >= 0L) resumeOffset + contentLength else -1L
        var appendedBytes: Long = 0L
        try {
            connection.inputStream.use { inputStream: InputStream ->
                FileOutputStream(partFile, resumeOffset > 0L).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int = inputStream.read(buffer)
                    while (bytesRead != -1) {
                        if (isStopped) {
                            // download was cancelled - the partial file stays on disk for resuming
                            return false
                        }
                        outputStream.write(buffer, 0, bytesRead)
                        appendedBytes += bytesRead
                        // throttle progress updates
                        val now: Long = System.currentTimeMillis()
                        if (now - lastNotificationUpdate > PROGRESS_UPDATE_INTERVAL) {
                            lastNotificationUpdate = now
                            val currentBytes: Long = resumeOffset + appendedBytes
                            updateProgress(currentBytes, totalBytes)
                        }
                        bytesRead = inputStream.read(buffer)
                    }
                    outputStream.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
        updateProgress(resumeOffset + appendedBytes, totalBytes)
        return true
    }


    /* Publishes the current progress to WorkManager and updates the notification */
    private suspend fun updateProgress(currentBytes: Long, totalBytes: Long) {
        val percent: Int = if (totalBytes > 0L) ((currentBytes * 100L) / totalBytes).toInt().coerceIn(0, 99) else 0
        try {
            setProgress(
                workDataOf(
                    Keys.PROGRESS_OFFLINE_MAP_PERCENT to percent,
                    Keys.PROGRESS_OFFLINE_MAP_BYTES to currentBytes,
                    Keys.PROGRESS_OFFLINE_MAP_TOTAL to totalBytes
                )
            )
        } catch (e: IllegalStateException) {
            // work is no longer running - ignore
        }
        updateNotification(currentBytes, totalBytes)
    }


    /* Overrides getForegroundInfo from CoroutineWorker */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0L, -1L)
    }


    /* Creates the foreground info with a progress notification */
    private fun createForegroundInfo(currentBytes: Long, totalBytes: Long): ForegroundInfo {
        createNotificationChannel()
        val notification = buildNotification(currentBytes, totalBytes, ongoing = true)
        return ForegroundInfo(Keys.OFFLINE_MAP_NOTIFICATION_ID, notification)
    }


    /* Updates the progress notification */
    private fun updateNotification(currentBytes: Long, totalBytes: Long) {
        createNotificationChannel()
        val notification = buildNotification(currentBytes, totalBytes, ongoing = true)
        try {
            NotificationManagerCompat.from(applicationContext).notify(Keys.OFFLINE_MAP_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // notification permission not granted - ignore
        }
    }


    /* Shows the completion notification */
    private fun showResultNotification(success: Boolean) {
        createNotificationChannel()
        val text: String = if (success) {
            applicationContext.getString(R.string.offline_map_notification_complete)
        } else {
            applicationContext.getString(R.string.offline_map_notification_failed)
        }
        val notification = NotificationCompat.Builder(applicationContext, Keys.OFFLINE_MAP_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_icon_small_24dp)
            .setContentTitle(applicationContext.getString(R.string.offline_map_notification_title))
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(Keys.OFFLINE_MAP_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // notification permission not granted - ignore
        }
    }


    /* Builds a progress notification */
    private fun buildNotification(currentBytes: Long, totalBytes: Long, ongoing: Boolean): android.app.Notification {
        val builder = NotificationCompat.Builder(applicationContext, Keys.OFFLINE_MAP_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_icon_small_24dp)
            .setContentTitle(applicationContext.getString(R.string.offline_map_notification_title))
            .setContentText(applicationContext.getString(R.string.offline_map_notification_downloading))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (totalBytes > 0L) {
            builder.setProgress(totalBytes.toInt(), currentBytes.toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }


    /* Creates the notification channel for offline map downloads */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(Keys.OFFLINE_MAP_NOTIFICATION_CHANNEL) == null) {
                val notificationChannel = NotificationChannel(
                    Keys.OFFLINE_MAP_NOTIFICATION_CHANNEL,
                    applicationContext.getString(R.string.offline_map_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(notificationChannel)
            }
        }
    }


    /* Companion object */
    companion object {
        const val HTTP_PARTIAL_CONTENT: Int = 206
        const val HTTP_OK: Int = 200
        val HTTP_SUCCESS_RANGE: IntRange = 200..299
        const val CONNECT_TIMEOUT: Int = 15000
        const val READ_TIMEOUT: Int = 30000
        const val BUFFER_SIZE: Int = 65536
        const val PROGRESS_UPDATE_INTERVAL: Long = 500L
    }

}
