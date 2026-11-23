/*
 * MapsforgeTileSource.kt
 * Implements the MapsforgeTileSource class
 * A MapsforgeTileSource renders offline vector map files (mapsforge format, e.g. jiangsu.map)
 * into osmdroid map tiles. No network connection is ever used - all data comes from the local file.
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

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.graphics.AndroidTileBitmap
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.renderer.DirectRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.y20k.trackbook.Keys
import java.io.File


/*
 * MapsforgeTileSource class
 */
class MapsforgeTileSource private constructor(private val mapDataStore: MapDataStore, renderTheme: InternalRenderTheme, zoomMax: Int) : BitmapTileSourceBase(NAME, MIN_ZOOM, zoomMax, TILE_SIZE, IMAGE_FILE_ENDING, COPYRIGHT_NOTICE) {


    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(MapsforgeTileSource::class.java)


    /* Main class variables */
    private val displayModel = org.mapsforge.map.model.DisplayModel()
    private val renderThemeFuture: RenderThemeFuture
    private val renderer: DirectRenderer


    /* Init block */
    init {
        // parse the render theme on a background thread - parsing can take a moment on first launch
        renderThemeFuture = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, renderTheme, displayModel)
        Thread(renderThemeFuture).start()
        // vector tile renderer - rendersLabels enabled, hillshading disabled
        renderer = DirectRenderer(mapDataStore, AndroidGraphicFactory.INSTANCE, true, null)
    }


    /*
     * Companion object
     */
    companion object {

        /* Constants */
        private const val NAME: String = "JiangsuMapsforge"
        private const val MIN_ZOOM: Int = 0
        private const val TILE_SIZE: Int = 256
        private const val IMAGE_FILE_ENDING: String = ".png"
        const val COPYRIGHT_NOTICE: String = "(c) OpenStreetMap contributors"

        /* Creates a tile source from a local mapsforge map file (e.g. jiangsu.map) */
        fun fromFile(mapFile: File): MapsforgeTileSource {
            val mapDataStore: MultiMapDataStore = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
            mapDataStore.addMapDataStore(MapFile(mapFile), false, false)
            return MapsforgeTileSource(mapDataStore, InternalRenderTheme.DEFAULT, Keys.OFFLINE_MAP_MAX_ZOOM)
        }
    }


    /* Returns the geographic bounds covered by the map file */
    fun getBounds(): BoundingBox {
        return mapDataStore.boundingBox()
    }


    /* Renders an osmdroid tile (given as tile index) synchronously - call from background thread */
    @Synchronized
    fun renderTile(mapTileIndex: Long): Drawable? {
        val tile = Tile(MapTileIndex.getX(mapTileIndex), MapTileIndex.getY(mapTileIndex), MapTileIndex.getZoom(mapTileIndex).toByte(), TILE_SIZE)
        // the map file does not cover this tile
        if (!mapDataStore.supportsTile(tile)) {
            return null
        }
        displayModel.setFixedTileSize(TILE_SIZE)
        return try {
            val rendererJob = RendererJob(tile, mapDataStore, renderThemeFuture, displayModel, org.mapsforge.map.model.DisplayModel.getDefaultUserScaleFactor(), false, false)
            val tileBitmap = renderer.executeJob(rendererJob) ?: return null
            val bitmap: Bitmap = AndroidGraphicFactory.getBitmap(tileBitmap as AndroidTileBitmap)
            BitmapDrawable(bitmap)
        } catch (e: Exception) {
            LogHelper.w(TAG, "Mapsforge tile generation failed for tile: $mapTileIndex $e")
            null
        } catch (e: OutOfMemoryError) {
            LogHelper.e(TAG, "Out of memory while generating mapsforge tile: $mapTileIndex $e")
            System.gc()
            null
        }
    }


    /* Releases the map file and all rendering resources */
    fun dispose() {
        try {
            mapDataStore.close()
        } catch (e: Exception) {
            LogHelper.w(TAG, "Unable to close mapsforge map data store. $e")
        }
    }

}
