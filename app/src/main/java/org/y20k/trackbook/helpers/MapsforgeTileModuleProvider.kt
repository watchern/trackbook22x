/*
 * MapsforgeTileModuleProvider.kt
 * Implements the MapsforgeTileModuleProvider class
 * A MapsforgeTileModuleProvider plugs a MapsforgeTileSource into osmdroid's tile provider chain:
 * it renders vector tiles from a local mapsforge map file on worker threads and stores the
 * rendered tiles as PNG in osmdroid's tile cache (SqlTileWriter). It never uses the network.
 *
 * Based on the well known integration pattern of osmdroid and mapsforge
 * (see https://github.com/osmdroid/osmdroid/tree/master/osmdroid-mapsforge)
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

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.modules.SqlTileWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream


/*
 * MapsforgeTileModuleProvider class
 */
class MapsforgeTileModuleProvider(registerReceiver: IRegisterReceiver, private val tileSource: MapsforgeTileSource) : MapTileModuleProviderBase(
    Configuration.getInstance().tileFileSystemThreads.toInt(),
    Configuration.getInstance().tileFileSystemMaxQueueSize.toInt()
) {


    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(MapsforgeTileModuleProvider::class.java)


    /* Main class variables */
    private val tileWriter: IFilesystemCache = SqlTileWriter()


    /* Overrides getName from MapTileModuleProviderBase */
    override fun getName(): String {
        return "Mapsforge Tiles Provider"
    }


    /* Overrides getThreadGroupName from MapTileModuleProviderBase */
    override fun getThreadGroupName(): String {
        return "mapsforgetileprovider"
    }


    /* Overrides getTileLoader from MapTileModuleProviderBase */
    override fun getTileLoader(): TileLoader {
        return MapsforgeTileLoader()
    }


    /* Overrides getUsesDataConnection from MapTileModuleProviderBase */
    override fun getUsesDataConnection(): Boolean {
        return false
    }


    /* Overrides getMinimumZoomLevel from MapTileModuleProviderBase */
    override fun getMinimumZoomLevel(): Int {
        return tileSource.minimumZoomLevel
    }


    /* Overrides getMaximumZoomLevel from MapTileModuleProviderBase */
    override fun getMaximumZoomLevel(): Int {
        return tileSource.maximumZoomLevel
    }


    /* Overrides setTileSource from MapTileModuleProviderBase - tile source is fixed, nothing to do */
    override fun setTileSource(tileSource: org.osmdroid.tileprovider.tilesource.ITileSource) {
        // empty on purpose
    }


    /* Overrides detach from MapTileModuleProviderBase */
    override fun detach() {
        tileWriter.onDetach()
        super.detach()
    }


    /*
     * MapsforgeTileLoader inner class
     */
    inner class MapsforgeTileLoader : TileLoader() {

        /* Overrides loadTile from TileLoader */
        override fun loadTile(pMapTileIndex: Long): Drawable? {
            // render the vector tile
            val drawable: Drawable = tileSource.renderTile(pMapTileIndex) ?: return null
            // store rendered tile as PNG in osmdroid's tile cache - speeds up subsequent displays
            if (drawable is BitmapDrawable) {
                try {
                    val byteArrayOutputStream = ByteArrayOutputStream()
                    drawable.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 0, byteArrayOutputStream)
                    tileWriter.saveFile(tileSource, pMapTileIndex, ByteArrayInputStream(byteArrayOutputStream.toByteArray()), null)
                } catch (e: Exception) {
                    LogHelper.d(TAG, "Unable to cache rendered mapsforge tile. $e")
                }
            }
            return drawable
        }

    }

}
