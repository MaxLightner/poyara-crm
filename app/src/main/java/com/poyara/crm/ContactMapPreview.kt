package com.poyara.crm

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/** Lightweight embedded preview: exactly the saved CRM coordinate, never auto-centers GPS.
 * OSM tiles are only requested when visible; the full map remains separate.
 */
@Composable
internal fun ContactMapPreview(latitude: Double, longitude: Double, title: String) {
    val context = LocalContext.current
    val map = remember(context, latitude, longitude, title) {
        val config = Configuration.getInstance()
        config.userAgentValue = "POYARA-CRM/0.12 (+https://github.com/MaxLightner/poyara-crm)"
        config.osmdroidBasePath = File(context.filesDir, "map_tiles").apply { mkdirs() }
        config.osmdroidTileCache = File(context.filesDir, "map_tiles/cache").apply { mkdirs() }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            setBuiltInZoomControls(false)
            controller.setZoom(16.5)
            val coordinate = GeoPoint(latitude, longitude)
            controller.setCenter(coordinate)
            val pin = Marker(this).apply {
                position = coordinate
                this.title = title.ifBlank { "موقعیت مجموعه" }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            overlays.add(pin)
            post { pin.showInfoWindow() }
            setOnTouchListener { _, _ -> true } // static thumbnail; full map button below
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView(
        factory = { map },
        modifier = Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(12.dp))
    )
}
