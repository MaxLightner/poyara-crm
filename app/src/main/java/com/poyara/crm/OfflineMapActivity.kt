package com.poyara.crm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A small, online-first map: only visible map tiles are fetched and standard
 * on-device tile cache is reused. No city/province tiles are pre-downloaded.
 * Client locations are in the CRM database and do not depend on map tiles.
 * GPS is requested independently from the basemap.
 */
class OfflineMapActivity : ComponentActivity() {
    private lateinit var db: CrmDb
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var filterButton: Button
    private lateinit var radiusButton: Button
    private val pins = mutableListOf<Marker>()
    private var userPin: Marker? = null
    private var selectedEntryId = 0L
    private var allowPick = false
    private var previewOnly = false
    private var previewLatitude: Double? = null
    private var previewLongitude: Double? = null
    private var previewLabel: String = "موقعیت مجموعه"
    private var currentTag = "همه"
    private var radiusMeters = 2000
    private var nearbyOrigin: Location? = null
    private var locationAction = "center"
    private var fetchingLocation = false
    private var fallbackStarted = false
    private var generation = 0
    private var pendingListener: LocationListener? = null
    private var cancellation: CancellationTokenSource? = null
    private val handler = Handler(Looper.getMainLooper())

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { responses ->
        if (responses.values.any { it }) checkGpsAndLocate()
        else message("برای نمایش موقعیت باید دسترسی موقعیت را تأیید کنی")
    }

    private val gpsResolution = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) checkGpsAndLocate()
        else message("موقعیت‌یابی روشن نشد؛ می‌توانی دستی روی نقشه علامت بزنی")
    }

    private val locationSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val manager = getSystemService(LocationManager::class.java)
        val enabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (enabled) requestLocationFix()
        else {
            status.text = "موقعیت گوشی همچنان خاموش است"
            message("می‌توانی بعداً موقعیت را فعال کنی یا روی نقشه علامت بزنی")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 64, 68)
        db = CrmDb(applicationContext)
        selectedEntryId = intent.getLongExtra("entry_id", 0L)
        allowPick = intent.getBooleanExtra("allow_pick_location", false)
        previewOnly = intent.getBooleanExtra("preview_only", false)
        if (intent.hasExtra("preview_latitude") && intent.hasExtra("preview_longitude")) {
            previewLatitude = intent.getDoubleExtra("preview_latitude", Double.NaN).takeIf { it in -90.0..90.0 }
            previewLongitude = intent.getDoubleExtra("preview_longitude", Double.NaN).takeIf { it in -180.0..180.0 }
        }
        previewLabel = intent.getStringExtra("preview_label")?.trim()?.take(90)
            ?.takeIf { it.isNotBlank() } ?: "موقعیت مجموعه"

        // OSM public tiles: only user-visible tiles are fetched, with an identifying
        // User-Agent and copyright attribution. There is no offline bulk download.
        val config = Configuration.getInstance()
        config.userAgentValue =
            "POYARA-CRM/0.7 (+https://github.com/MaxLightner/poyara-crm)"
        config.osmdroidBasePath = File(filesDir, "map_tiles").apply { mkdirs() }
        config.osmdroidTileCache = File(filesDir, "map_tiles/cache").apply { mkdirs() }
        config.tileFileSystemCacheMaxBytes = 64L * 1024 * 1024
        config.tileFileSystemCacheTrimBytes = 48L * 1024 * 1024

        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            minZoomLevel = 5.0
            maxZoomLevel = 19.0
            controller.setZoom(14.0)
        }
        val starting = db.all().firstOrNull {
            it.latitude != null && it.longitude != null &&
                (selectedEntryId == 0L || it.id == selectedEntryId)
        }
        val initialLat = if (intent.hasExtra("preview_latitude"))
            intent.getDoubleExtra("preview_latitude", 36.2972) else starting?.latitude ?: 36.2972
        val initialLon = if (intent.hasExtra("preview_longitude"))
            intent.getDoubleExtra("preview_longitude", 59.6067) else starting?.longitude ?: 59.6067
        mapView.controller.setCenter(GeoPoint(initialLat, initialLon))
        if (starting != null || intent.hasExtra("preview_latitude")) mapView.controller.setZoom(17.0)

        showScreen()
        if (!previewOnly) enableMapTap()
        showPins()
        updateStatus()
        // When showing a draft coordinate, there may be no saved CRM entry yet.
        // Keep the map centered on the exact selected point and open its label.
        if (previewOnly) {
            val lat = previewLatitude ?: starting?.latitude
            val lon = previewLongitude ?: starting?.longitude
            if (lat != null && lon != null) {
                mapView.post {
                    mapView.controller.setZoom(17.0)
                    mapView.controller.setCenter(GeoPoint(lat, lon))
                    pins.firstOrNull()?.showInfoWindow()
                    mapView.invalidate()
                }
            }
        }
        // A contact preview must remain centered on the saved location, NOT GPS.
        if (!previewOnly && starting == null && !intent.hasExtra("preview_latitude")) {
            locationAction = "center"
            locateWithPermission()
        }
    }

    private fun showScreen() {
        val root = FrameLayout(this)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(7))
            setBackgroundColor(Color.argb(238, 12, 63, 69))
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(6))
        }
        header.addView(status)
        val filters = LinearLayout(this).apply { gravity = Gravity.CENTER }
        filterButton = makeButton("همه دسته‌ها") {
            val choices = listOf("همه", "مبلمان", "کابینت", "معماری", "پیگیری", "مهم")
            currentTag = choices[(choices.indexOf(currentTag) + 1) % choices.size]
            filterButton.text = currentTag
            showPins()
            updateStatus()
        }
        filters.addView(filterButton)
        filters.addView(makeButton("نمایش همه") {
            nearbyOrigin = null
            showPins()
            updateStatus()
        })
        if (!previewOnly) header.addView(filters)
        else header.addView(makeButton("بازگشت به پرونده") { finish() })
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(6), dp(5), dp(6))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(244, 255, 255, 255))
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actions.addView(makeButton("اطراف من") {
            locationAction = "nearby"
            locateWithPermission()
        })
        actions.addView(makeButton("موقعیت من") {
            locationAction = "center"
            locateWithPermission()
        })
        if (!previewOnly && (selectedEntryId > 0 || allowPick)) {
            actions.addView(makeButton("ثبت GPS") {
                locationAction = "save"
                locateWithPermission()
            })
        }
        if (!previewOnly) bottom.addView(actions)
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
            if (nearbyOrigin != null) {
                showPins()
                updateStatus()
            }
        }
        if (!previewOnly) bottom.addView(radiusButton)
        bottom.addView(TextView(this).apply {
            text = "© OpenStreetMap contributors · فقط بخش‌های دیده‌شده نقشه دریافت و کش می‌شوند"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minHeight = dp(44)
        setTextColor(Color.rgb(7, 76, 70))
        setOnClickListener { action() }
    }

    private fun visibleContacts(): List<ContactEntry> = db.all().filter { entry ->
        entry.latitude != null && entry.longitude != null &&
            (currentTag == "همه" || currentTag in entry.tags) &&
            (nearbyOrigin?.let { origin ->
                val results = FloatArray(1)
                Location.distanceBetween(
                    origin.latitude, origin.longitude,
                    entry.latitude, entry.longitude, results
                )
                results[0] <= radiusMeters
            } ?: true)
    }

    private fun updateStatus() {
        if (fetchingLocation) return
        val count = visibleContacts().size
        status.text = when {
            previewOnly ->
                previewLabel + " · موقعیت انتخاب‌شده روی نقشه بزرگ"
            selectedEntryId > 0 || allowPick ->
                "برای ثبت موقعیت، روی نقشه بزن یا «ثبت GPS» را انتخاب کن"
            nearbyOrigin != null ->
                count.toString() + " مشتری در شعاع " + radiusMeters.toString() + " متر"
            else -> count.toString() + " مشتری دارای موقعیت · نقشه با اینترنت و کش محلی"
        }
    }

    private fun showPins() {
        pins.forEach { mapView.overlays.remove(it) }
        pins.clear()

        if (previewOnly) {
            // The preview may be a not-yet-saved location. Never infer a marker
            // from the entire CRM collection or silently substitute GPS.
            val saved = db.all().firstOrNull { it.id == selectedEntryId }
            val lat = previewLatitude ?: saved?.latitude
            val lon = previewLongitude ?: saved?.longitude
            if (lat != null && lon != null) {
                val label = previewLabel.takeIf { it != "موقعیت مجموعه" }
                    ?: saved?.company?.ifBlank { saved.name }?.takeIf { it.isNotBlank() }
                    ?: "موقعیت مجموعه"
                val pin = Marker(mapView).apply {
                    position = GeoPoint(lat, lon)
                    title = label
                    icon = circleIcon(Color.rgb(11, 130, 98), 30)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { marker, _ ->
                        marker.showInfoWindow()
                        true
                    }
                }
                pins += pin
                mapView.overlays.add(pin)
                mapView.post { if (pins.contains(pin)) pin.showInfoWindow() }
            }
            mapView.invalidate()
            return
        }

        val groups = visibleContacts().groupBy { entry ->
            val company = entry.groupId?.toString() ?: entry.company.ifBlank { entry.id.toString() }
            company + ":" + String.format(
                Locale.US, "%.5f:%.5f", entry.latitude!!, entry.longitude!!
            )
        }
        groups.values.forEach { group ->
            val first = group.first()
            val pin = Marker(mapView).apply {
                position = GeoPoint(first.latitude!!, first.longitude!!)
                title = first.company.ifBlank { first.name }.ifBlank {
                    first.note.lineSequence().firstOrNull().orEmpty().take(60)
                }.ifBlank { "پرونده بدون عنوان" }
                icon = circleIcon(Color.rgb(11, 130, 98), 25)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ ->
                    openContact(group)
                    true
                }
            }
            pins += pin
            mapView.overlays.add(pin)
        }
        mapView.invalidate()
    }

    private fun circleIcon(color: Int, size: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(3), Color.WHITE)
        setSize(dp(size), dp(size))
    }

    private fun enableMapTap() {
        mapView.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                if (point == null || (selectedEntryId <= 0 && !allowPick)) return false
                savePoint(point.latitude, point.longitude, "manual")
                return true
            }
            override fun longPressHelper(point: GeoPoint?): Boolean = false
        }))
    }

    private fun savePoint(latitude: Double, longitude: Double, source: String) {
        if (previewOnly) return
        if (selectedEntryId > 0) db.setLocation(selectedEntryId, latitude, longitude, source)
        if (selectedEntryId <= 0 && !allowPick) return
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra("latitude", latitude)
            putExtra("longitude", longitude)
            putExtra("location_source", source)
        })
        message("موقعیت انتخاب شد")
        finish()
    }

    private fun openContact(group: List<ContactEntry>) {
        val contact = group.first()
        val lat = contact.latitude ?: return
        val lon = contact.longitude ?: return
        val label = contact.name.ifBlank { contact.company.ifBlank { contact.phone } }
        android.app.AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(group.size.toString() + " مخاطب · " +
                group.map { it.phone }.filter { it.isNotBlank() }.joinToString(" / ") +
                "\n\n" + contact.note.take(220))
            .setPositiveButton("پرونده") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java)
                    .putExtra("record_id", contact.id))
            }
            .setNeutralButton("مسیریابی") { _, _ ->
                runCatching {
                    val destination = Uri.parse("geo:0,0?q=" + lat + "," + lon)
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_VIEW, destination),
                        "انتخاب مسیریاب"
                    ))
                }.onFailure { message("مسیریاب سازگار پیدا نشد") }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun locateWithPermission() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) checkGpsAndLocate()
        else locationPermissions.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun checkGpsAndLocate() {
        val manager = getSystemService(LocationManager::class.java)
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            status.text = "موقعیت گوشی خاموش است؛ لطفاً روشن کن"
            try {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                    .build()
                LocationServices.getSettingsClient(this).checkLocationSettings(
                    LocationSettingsRequest.Builder().addLocationRequest(request).build()
                ).addOnSuccessListener { requestLocationFix() }
                    .addOnFailureListener { error ->
                        if (error is ResolvableApiException) {
                            runCatching {
                                gpsResolution.launch(
                                    IntentSenderRequest.Builder(error.resolution).build()
                                )
                            }.onFailure { openAndroidLocationSettings() }
                        } else openAndroidLocationSettings()
                    }
            } catch (_: Exception) {
                openAndroidLocationSettings()
            }
        } else requestLocationFix()
    }

    private fun openAndroidLocationSettings() {
        locationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun requestLocationFix() {
        if (fetchingLocation) return
        fetchingLocation = true
        fallbackStarted = false
        generation += 1
        val thisRequest = generation
        status.text = "در حال دریافت موقعیت دقیق گوشی..."
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val priority = if (fine) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        try {
            cancellation = CancellationTokenSource()
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(priority, cancellation!!.token)
                .addOnSuccessListener { location ->
                    if (thisRequest != generation || !fetchingLocation) return@addOnSuccessListener
                    if (location != null) finishLocationFix(location)
                    else requestProviderFallback(thisRequest)
                }
                .addOnFailureListener {
                    if (thisRequest == generation) requestProviderFallback(thisRequest)
                }
        } catch (_: Exception) {
            requestProviderFallback(thisRequest)
        }
        handler.postDelayed({
            if (thisRequest == generation && fetchingLocation) {
                requestProviderFallback(thisRequest)
            }
        }, 9000)
    }

    @Suppress("DEPRECATION")
    private fun requestProviderFallback(requestId: Int) {
        if (!fetchingLocation || requestId != generation || fallbackStarted) return
        fallbackStarted = true
        status.text = "در حال جست‌وجوی GPS و شبکه؛ ممکن است کمی طول بکشد..."
        val manager = getSystemService(LocationManager::class.java)
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            runCatching {
                manager.isProviderEnabled(provider) && (fine ||
                    (provider == LocationManager.NETWORK_PROVIDER && coarse))
            }.getOrDefault(false)
        }
        if (providers.isEmpty()) {
            finishWithoutLocation("GPS خاموش است یا اجازه موقعیت کافی نیست")
            return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (requestId == generation && fetchingLocation) finishLocationFix(location)
            }
        }
        pendingListener = listener
        try {
            providers.forEach { provider ->
                manager.requestLocationUpdates(provider, 1500L, 0f, listener, Looper.getMainLooper())
            }
            val bestRecent = providers.mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.filter { location ->
                System.currentTimeMillis() - location.time < 5 * 60 * 1000L
            }.maxByOrNull { it.time }
            if (bestRecent != null) finishLocationFix(bestRecent)
        } catch (_: SecurityException) {
            finishWithoutLocation("اجازه موقعیت کافی نیست")
        } catch (_: Exception) {
            finishWithoutLocation("خطا در دریافت GPS؛ دوباره تلاش کن")
        }
        handler.postDelayed({
            if (requestId == generation && fetchingLocation)
                finishWithoutLocation("موقعیت پیدا نشد؛ در فضای باز دوباره تلاش کن")
        }, 25000)
    }

    private fun stopPendingLocation() {
        cancellation?.cancel()
        cancellation = null
        pendingListener?.let { listener ->
            runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
        }
        pendingListener = null
    }

    private fun finishWithoutLocation(text: String) {
        fetchingLocation = false
        generation += 1
        stopPendingLocation()
        status.text = text
        message(text)
    }

    private fun finishLocationFix(location: Location) {
        if (!fetchingLocation) return
        fetchingLocation = false
        generation += 1
        stopPendingLocation()
        mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
        mapView.controller.setZoom(16.0)
        userPin?.let { mapView.overlays.remove(it) }
        userPin = Marker(mapView).apply {
            position = GeoPoint(location.latitude, location.longitude)
            title = "موقعیت من"
            icon = circleIcon(Color.rgb(36, 102, 234), 22)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapView.overlays.add(userPin)
        when (locationAction) {
            "save" -> {
                savePoint(location.latitude, location.longitude, "gps")
                return
            }
            "nearby" -> {
                nearbyOrigin = location
                showPins()
                if (visibleContacts().isEmpty()) message("در شعاع انتخاب‌شده مشتری ثبت نشده است")
            }
        }
        updateStatus()
        mapView.invalidate()
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun dp(amount: Int): Int =
        (amount * resources.displayMetrics.density).roundToInt()

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopPendingLocation()
        handler.removeCallbacksAndMessages(null)
        if (::mapView.isInitialized) mapView.onDetach()
        db.close()
        super.onDestroy()
    }
}
