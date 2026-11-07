package com.poyara.crm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import java.util.Locale
import kotlin.math.*

/**
 * Offline-only Mapforge map. CRM coordinates work even without a basemap.
 * Street rendering is enabled by importing a Mapsforge .map file once.
 */
class OfflineMapActivity : ComponentActivity() {
    private lateinit var db: CrmDb
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var filterButton: Button
    private lateinit var radiusButton: Button
    private var mapData: MapFile? = null
    private var renderer: TileRendererLayer? = null
    private var cache: TileCache? = null
    private val markers = mutableListOf<Marker>()
    private var selectingEntryId = 0L
    private var filterTag = "همه"
    private var radiusMeters = 2000
    private var nextLocationAction = "nearby"
    private val mapFile by lazy { File(filesDir, "maps/offline.map") }

    private val mapPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            status.text = "در حال واردکردن نقشه آفلاین..."
            Thread {
                try {
                    val target = mapFile
                    target.parentFile?.mkdirs()
                    val temporary = File(target.parentFile, "incoming.map")
                    contentResolver.openInputStream(uri)?.use { input ->
                        temporary.outputStream().use { input.copyTo(it) }
                    } ?: error("فایل باز نشد")
                    require(temporary.length() > 0) { "فایل نقشه خالی است" }
                    // Validate before replacing the previous working map.
                    val candidate = MapFile(temporary)
                    candidate.close()
                    val previous = File(target.parentFile, "previous.map")
                    if (previous.exists()) previous.delete()
                    val hadPrevious = target.exists()
                    if (hadPrevious) require(target.renameTo(previous)) { "فایل نقشه قبلی در دسترس نیست" }
                    if (!temporary.renameTo(target)) {
                        if (hadPrevious) previous.renameTo(target)
                        error("ذخیره نقشه انجام نشد")
                    }
                    if (hadPrevious) previous.delete()
                    runOnUiThread {
                        loadBasemap()
                        updateStatus()
                    }
                } catch (ex: Exception) {
                    runOnUiThread {
                        status.text = "فایل نقشه معتبر نبود؛ دوباره انتخاب کن."
                        Toast.makeText(this, ex.localizedMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) requestLocation()
        else Toast.makeText(this, "برای GPS اجازه موقعیت لازم است", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 62, 68)
        AndroidGraphicFactory.createInstance(application)
        db = CrmDb(applicationContext)
        selectingEntryId = intent.getLongExtra("entry_id", 0L)
        mapView = MapView(this).apply {
            setBuiltInZoomControls(true)
            setZoomLevel(15.toByte())
        }
        val located = db.all().firstOrNull { it.latitude != null && it.longitude != null }
        mapView.setCenter(
            if (located?.latitude != null && located.longitude != null) {
                LatLong(located.latitude, located.longitude)
            } else LatLong(36.30, 59.60)
        )
        createScreen()
        loadBasemap()
        showPins()
        updateStatus()
        setupTapToSelect()
    }

    private fun createScreen() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(232, 239, 239))
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.argb(242, 14, 65, 70))
        }
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        top.addView(status)
        val topButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        topButtons.addView(makeButton("نقشه آفلاین") {
            mapPicker.launch(arrayOf("*/*"))
        })
        filterButton = makeButton("همه") { cycleFilter() }
        topButtons.addView(filterButton)
        top.addView(topButtons)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setBackgroundColor(Color.argb(238, 255, 255, 255))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(makeButton("اطراف من") {
            nextLocationAction = "nearby"
            locateWithPermission()
        })
        row.addView(makeButton("موقعیت من") {
            nextLocationAction = "center"
            locateWithPermission()
        })
        if (selectingEntryId > 0) {
            row.addView(makeButton("ثبت GPS") {
                nextLocationAction = "save"
                locateWithPermission()
            })
        }
        bottom.addView(row)
        radiusButton = makeButton("شعاع ۲ کیلومتر") {
            radiusMeters = when (radiusMeters) {
                500 -> 2000
                2000 -> 5000
                else -> 500
            }
            radiusButton.text = when (radiusMeters) {
                500 -> "شعاع ۵۰۰ متر"
                2000 -> "شعاع ۲ کیلومتر"
                else -> "شعاع ۵ کیلومتر"
            }
        }
        bottom.addView(radiusButton)
        val attribution = TextView(this).apply {
            text = "© OpenStreetMap contributors · نقشه فقط از فایل محلی خوانده می‌شود"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }
        bottom.addView(attribution)
        val params = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        params.bottomMargin = dp(12)
        root.addView(bottom, params)
        setContentView(root)
    }

    private fun makeButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.rgb(5, 73, 68))
        minHeight = dp(46)
        setOnClickListener { action() }
    }

    private fun updateStatus() {
        val count = visibleContacts().size
        status.text = if (selectingEntryId > 0) {
            "برای ثبت نشانی این مشتری، روی نقشه لمس کن یا «ثبت GPS» بزن."
        } else if (mapFile.isFile) {
            "$count مخاطب دارای موقعیت · نقشه کاملاً آفلاین"
        } else {
            "$count مخاطب دارای موقعیت · برای خیابان‌ها فایل نقشه منطقه را وارد کن."
        }
    }

    private fun visibleContacts(): List<ContactEntry> = db.all().filter {
        it.latitude != null && it.longitude != null &&
            (filterTag == "همه" || filterTag in it.tags)
    }

    private fun cycleFilter() {
        val choices = listOf("همه", "مبلمان", "کابینت", "معماری", "پیگیری")
        filterTag = choices[(choices.indexOf(filterTag) + 1) % choices.size]
        filterButton.text = filterTag
        showPins()
        updateStatus()
    }

    private fun loadBasemap() {
        renderer?.let { mapView.layerManager.layers.remove(it) }
        renderer = null
        mapData?.close()
        mapData = null
        if (!mapFile.isFile) return
        try {
            val data = MapFile(mapFile)
            val tileCache = AndroidUtil.createTileCache(
                this, "offline-cache",
                mapView.model.displayModel.tileSize,
                1f, mapView.model.frameBufferModel.overdrawFactor
            )
            val layer = TileRendererLayer(
                tileCache, data, mapView.model.mapViewPosition, AndroidGraphicFactory.INSTANCE
            )
            layer.setXmlRenderTheme(MapsforgeThemes.DEFAULT)
            mapView.layerManager.layers.add(0, layer)
            mapData = data
            cache = tileCache
            renderer = layer
            if (db.all().none { it.latitude != null && it.longitude != null }) {
                data.startPosition()?.let { mapView.setCenter(it) }
                data.startZoomLevel()?.let { mapView.setZoomLevel(it) }
            }
            mapView.layerManager.redrawLayers()
        } catch (e: Exception) {
            status.text = "این فایل نقشه قابل نمایش نیست."
        }
    }

    private fun showPins() {
        markers.forEach { mapView.layerManager.layers.remove(it) }
        markers.clear()
        val groups = visibleContacts().groupBy { entry ->
            val locationKey = String.format(
                Locale.US, "%.5f:%.5f", entry.latitude!!, entry.longitude!!
            )
            "${entry.company.ifBlank { entry.id.toString() }}:$locationKey"
        }
        groups.values.forEach { group ->
            val first = group.first()
            val lat = first.latitude ?: return@forEach
            val lon = first.longitude ?: return@forEach
            val size = dp(30)
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (group.any { "مهم" in it.tags }) Color.rgb(240, 127, 45)
                    else Color.rgb(0, 119, 103))
                setStroke(dp(3), Color.WHITE)
                setSize(size, size)
            }
            val bitmap = AndroidGraphicFactory.convertToBitmap(drawable)
            val marker = object : Marker(LatLong(lat, lon), bitmap, 0, 0) {
                override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
                    if (contains(layerXY, tapXY, mapView)) {
                        runOnUiThread { showContact(group) }
                        return true
                    }
                    return false
                }
            }
            markers.add(marker)
            mapView.layerManager.layers.add(marker)
        }
        mapView.layerManager.redrawLayers()
    }

    private fun showContact(group: List<ContactEntry>) {
        val first = group.first()
        val title = first.company.ifBlank {
            first.note.lineSequence().firstOrNull()?.take(32) ?: first.phone
        }
        val phones = group.map { it.phone }.filter { it.isNotBlank() }.distinct()
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(
                "${group.size} مخاطب\n${phones.joinToString(" | ")}\n\n${first.note.take(190)}"
            )
            .setPositiveButton("پرونده") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java).putExtra("record_id", first.id))
            }
            .setNeutralButton("تماس") { _, _ ->
                if (first.phone.isNotBlank()) openDialer(this, first.phone)
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private var downX = 0f
    private var downY = 0f
    private fun setupTapToSelect() {
        mapView.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val distance = hypot(event.x - downX, event.y - downY)
                    if (selectingEntryId > 0L && distance < dp(12)) {
                        val point = mapView.mapViewProjection.fromPixels(
                            event.x.toDouble(), event.y.toDouble()
                        )
                        if (point != null) {
                            savePoint(point.latitude, point.longitude, "manual")
                        }
                    }
                }
            }
            false // Let Mapsforge handle pan, zoom and marker taps.
        }
    }

    private fun savePoint(lat: Double, lon: Double, source: String) {
        val id = selectingEntryId
        if (id <= 0L) return
        db.setLocation(id, lat, lon, source)
        selectingEntryId = 0L
        mapView.setCenter(LatLong(lat, lon))
        showPins()
        updateStatus()
        Toast.makeText(this, "موقعیت مشتری ذخیره شد", Toast.LENGTH_LONG).show()
    }

    private fun locateWithPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        } else {
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun requestLocation() {
        val manager = getSystemService(LocationManager::class.java)
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val provider = when {
            hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            Toast.makeText(this, "موقعیت گوشی خاموش است", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "در حال دریافت موقعیت گوشی..."
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(provider, null, mainExecutor) { location ->
                    onLocationReady(location)
                }
            } else {
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) { onLocationReady(location) }
                }, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, "اجازه دسترسی به موقعیت صادر نشده", Toast.LENGTH_LONG).show()
        }
    }

    private fun onLocationReady(location: Location?) {
        if (location == null) {
            Toast.makeText(this, "موقعیت پیدا نشد، دوباره تلاش کن", Toast.LENGTH_LONG).show()
            updateStatus()
            return
        }
        mapView.setCenter(LatLong(location.latitude, location.longitude))
        mapView.setZoomLevel(15.toByte())
        when (nextLocationAction) {
            "save" -> {
                if (selectingEntryId > 0L) savePoint(location.latitude, location.longitude, "gps")
            }
            "nearby" -> showNearby(location)
        }
        updateStatus()
    }

    private fun showNearby(location: Location) {
        val groups = visibleContacts().filter { entry ->
            val distances = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                entry.latitude!!, entry.longitude!!, distances
            )
            distances[0] <= radiusMeters
        }.distinctBy {
            it.company.ifBlank { it.id.toString() } + ":" +
                String.format(Locale.US, "%.5f,%.5f", it.latitude!!, it.longitude!!)
        }
        if (groups.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("مشتریان اطراف")
                .setMessage("در شعاع انتخاب‌شده، مشتری ثبت‌شده‌ای پیدا نشد.")
                .setPositiveButton("باشه", null).show()
            return
        }
        val labels = groups.map { entry ->
            entry.company.ifBlank { entry.note.take(35).ifBlank { entry.phone } }
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("${groups.size} مشتری در شعاع ${radiusMeters} متر")
            .setItems(labels) { _, index -> showContact(listOf(groups[index])) }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        markers.clear()
        mapView.destroyAll()
        mapData?.close()
        db.close()
        super.onDestroy()
    }
}
