/*
 * OfflineTileProvider.kt
 * Implements the OfflineTileProvider class
 * An OfflineTileProvider chains the offline tile sources of Trackbook:
 * 1. tiles already rendered and cached by osmdroid (SQLite tile cache) - fastest
 * 2. the mapsforge vector map file (jiangsu.map) - renders any zoom level on the fly
 * 3. an optional MBTiles raster archive (nanjing.mbtiles, z12-15) - debug fallback
 * It never uses the network.
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
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.ArchiveFileFactory
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileSqlCacheProvider
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File


/*
 * OfflineTileProvider class
 */
class OfflineTileProvider constructor(context: Context, mapsforgeTileSource: MapsforgeTileSource, mbtilesFile: File?) : MapTileProviderArray(
    mapsforgeTileSource,
    SimpleRegisterReceiver(context),
    buildModuleProviders(context, mapsforgeTileSource, mbtilesFile)
)


/*
 * Builds the module provider chain: tile cache -> mapsforge renderer -> mbtiles archive
 */
private fun buildModuleProviders(context: Context, mapsforgeTileSource: MapsforgeTileSource, mbtilesFile: File?): Array<org.osmdroid.tileprovider.modules.MapTileModuleProviderBase> {
    val TAG: String = LogHelper.makeLogTag(OfflineTileProvider::class.java)
    // 1. SQLite tile cache holding previously rendered tiles
    val cacheProvider = MapTileSqlCacheProvider(SimpleRegisterReceiver(context), mapsforgeTileSource)
    // 2. mapsforge vector renderer
    val mapsforgeProvider = MapsforgeTileModuleProvider(SimpleRegisterReceiver(context), mapsforgeTileSource)
    // 3. optional MBTiles raster archive (ignoreTileSource = true)
    if (mbtilesFile != null && mbtilesFile.exists()) {
        try {
            val archive: IArchiveFile? = ArchiveFileFactory.getArchiveFile(mbtilesFile)
            if (archive != null) {
                archive.setIgnoreTileSource(true)
                val archiveProvider = MapTileFileArchiveProvider(SimpleRegisterReceiver(context), mapsforgeTileSource, arrayOf(archive))
                LogHelper.i(TAG, "Offline tile sources: cache + mapsforge + mbtiles")
                return arrayOf(cacheProvider, mapsforgeProvider, archiveProvider)
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "Unable to open MBTiles archive $mbtilesFile. $e")
        }
    }
    LogHelper.i(TAG, "Offline tile sources: cache + mapsforge")
    return arrayOf(cacheProvider, mapsforgeProvider)
}
