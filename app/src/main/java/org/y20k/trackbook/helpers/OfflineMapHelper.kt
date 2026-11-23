/*
 * OfflineMapHelper.kt
 * Implements the OfflineMapHelper object
 * An OfflineMapHelper provides helper methods for handling the offline map files of Trackbook
 * (mapsforge vector map jiangsu.map and optional MBTiles raster archive nanjing.mbtiles)
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


package org.y20k.trackbook.helpers

import android.content.Context
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.views.MapView
import org.y20k.trackbook.Keys
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


/*
 * OfflineMapHelper object
 */
object OfflineMapHelper {


    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(OfflineMapHelper::class.java)


    /* Magic byte of mapsforge binary map files - see org.mapsforge.map.reader.header.RequiredFields */
    private val MAPSFORGE_MAGIC_BYTE: String = "mapsforge binary OSM"


    /* Returns the directory holding the offline map files - kept inside the app's private storage */
    fun getOfflineMapDir(context: Context): File {
        val dir: File? = context.getExternalFilesDir(Keys.FOLDER_OFFLINE_MAPS)
        if (dir != null) {
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
        // fall back to internal storage if external storage is not available
        val internalDir = File(context.filesDir, Keys.FOLDER_OFFLINE_MAPS)
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir
    }


    /* Returns the mapsforge vector map file (jiangsu.map) */
    fun getMapsforgeFile(context: Context): File {
        return File(getOfflineMapDir(context), Keys.OFFLINE_MAP_MAPFORGE_FILE_NAME)
    }


    /* Returns the partially downloaded mapsforge map file (.part) - used for resuming downloads */
    fun getMapsforgePartFile(context: Context): File {
        return File(getOfflineMapDir(context), Keys.OFFLINE_MAP_MAPFORGE_FILE_NAME + Keys.OFFLINE_MAP_PART_SUFFIX)
    }


    /* Returns the optional MBTiles raster archive (nanjing.mbtiles) */
    fun getMbtilesFile(context: Context): File {
        return File(getOfflineMapDir(context), Keys.OFFLINE_MAP_MBTILES_FILE_NAME)
    }


    /* Checks if a valid mapsforge map file is installed */
    fun isMapsforgeMapInstalled(context: Context): Boolean {
        return isValidMapsforgeFile(getMapsforgeFile(context))
    }


    /* Checks if a valid MBTiles archive is installed (only checks header - SQLite files start with "SQLite format 3") */
    fun isMbtilesInstalled(context: Context): Boolean {
        val file: File = getMbtilesFile(context)
        if (!file.exists() || file.length() < Keys.OFFLINE_MAP_MIN_VALID_BYTES) return false
        return readHeader(file, 15).startsWith("SQLite format 3")
    }


    /* Checks if any offline map is installed */
    fun isOfflineMapInstalled(context: Context): Boolean {
        return isMapsforgeMapInstalled(context) || isMbtilesInstalled(context)
    }


    /* Returns the size in bytes of the installed offline map files (0 if none) */
    fun getInstalledMapSize(context: Context): Long {
        var size: Long = 0L
        val mapsforgeFile: File = getMapsforgeFile(context)
        val mbtilesFile: File = getMbtilesFile(context)
        if (isMapsforgeMapInstalled(context)) size += mapsforgeFile.length()
        if (isMbtilesInstalled(context)) size += mbtilesFile.length()
        return size
    }


    /* Checks the mapsforge magic byte and the minimum file size */
    fun isValidMapsforgeFile(file: File): Boolean {
        if (!file.exists() || file.length() < Keys.OFFLINE_MAP_MIN_VALID_BYTES) return false
        return readHeader(file, MAPSFORGE_MAGIC_BYTE.length) == MAPSFORGE_MAGIC_BYTE
    }


    /* Deletes all offline map files and the rendered tile cache */
    fun deleteOfflineMaps(context: Context): Boolean {
        var success: Boolean = true
        // delete map files and partial downloads
        val filesToDelete: List<File> = listOf(getMapsforgeFile(context), getMapsforgePartFile(context), getMbtilesFile(context))
        for (file: File in filesToDelete) {
            if (file.exists() && !file.delete()) {
                LogHelper.w(TAG, "Unable to delete offline map file ${file.absolutePath}")
                success = false
            }
        }
        // delete osmdroid's rendered tile cache (cache.db + expired files)
        try {
            val tileWriter = org.osmdroid.tileprovider.modules.SqlTileWriter()
            tileWriter.purgeCache()
            tileWriter.onDetach()
        } catch (e: Exception) {
            LogHelper.w(TAG, "Unable to purge tile cache. $e")
        }
        // notify map fragments that the offline map state changed
        PreferencesHelper.saveOfflineMapUpdatedAt()
        return success
    }


    /* Copies the map file bundled under assets/mbtiles/ to the offline map directory - debug aid */
    fun installBundledMapsforgeMap(context: Context): Boolean {
        val targetFile: File = getMapsforgeFile(context)
        if (isMapsforgeMapInstalled(context)) {
            // already installed - nothing to do
            return true
        }
        return try {
            context.assets.open(Keys.OFFLINE_MAP_ASSET_MAPFORGE_PATH).use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream, DEFAULT_BUFFER_SIZE)
                }
            }
            if (isValidMapsforgeFile(targetFile)) {
                PreferencesHelper.saveOfflineMapUpdatedAt()
                LogHelper.i(TAG, "Bundled mapsforge map installed: ${targetFile.absolutePath}")
                true
            } else {
                targetFile.delete()
                LogHelper.e(TAG, "Bundled mapsforge map is invalid.")
                false
            }
        } catch (e: IOException) {
            if (targetFile.exists()) targetFile.delete()
            LogHelper.e(TAG, "Unable to install bundled mapsforge map. $e")
            false
        }
    }


    /* Creates the offline tile provider - throws IllegalStateException if no offline map is installed */
    fun createOfflineTileProvider(context: Context): MapTileProviderBase {
        val mapsforgeFile: File = getMapsforgeFile(context)
        if (isValidMapsforgeFile(mapsforgeFile)) {
            return OfflineTileProvider(context, MapsforgeTileSource.fromFile(mapsforgeFile), getMbtilesFile(context))
        }
        val mbtilesFile: File = getMbtilesFile(context)
        if (isMbtilesInstalled(context)) {
            // CASE: only the MBTiles archive is available (no vector map)
            val tileSource = org.osmdroid.tileprovider.tilesource.XYTileSource(
                "NanjingOffline",
                Keys.OFFLINE_MAP_MBTILES_MIN_ZOOM,
                Keys.OFFLINE_MAP_MBTILES_MAX_ZOOM,
                256,
                ".png",
                arrayOf<String>(),
                MapsforgeTileSource.COPYRIGHT_NOTICE
            )
            val archive: org.osmdroid.tileprovider.modules.IArchiveFile? = org.osmdroid.tileprovider.modules.ArchiveFileFactory.getArchiveFile(mbtilesFile)
            if (archive != null) {
                archive.setIgnoreTileSource(true)
                val archiveProvider = org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider(
                    org.osmdroid.tileprovider.util.SimpleRegisterReceiver(context),
                    tileSource,
                    arrayOf(archive)
                )
                return org.osmdroid.tileprovider.MapTileProviderArray(
                    tileSource,
                    org.osmdroid.tileprovider.util.SimpleRegisterReceiver(context),
                    arrayOf(archiveProvider)
                )
            }
        }
        throw IllegalStateException("No offline map installed.")
    }


    /* Applies the offline tile provider to the given map view - returns false if no offline map is installed */
    fun applyOfflineTileProvider(context: Context, mapView: MapView): Boolean {
        if (!isOfflineMapInstalled(context)) {
            return false
        }
        return try {
            mapView.setTileProvider(createOfflineTileProvider(context))
            // force offline mode on the new provider - never request tiles from the network
            mapView.setUseDataConnection(false)
            true
        } catch (e: Exception) {
            LogHelper.e(TAG, "Unable to apply offline tile provider. $e")
            false
        }
    }


    /* Reads the first bytes of a file and returns them as an ASCII string */
    private fun readHeader(file: File, length: Int): String {
        return try {
            java.io.RandomAccessFile(file, "r").use { randomAccessFile ->
                val buffer = ByteArray(length)
                val read: Int = randomAccessFile.read(buffer, 0, length)
                if (read == length) String(buffer, Charsets.US_ASCII) else ""
            }
        } catch (e: IOException) {
            ""
        }
    }

}
