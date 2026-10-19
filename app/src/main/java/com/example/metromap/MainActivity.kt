package com.example.metromap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---- Metro palette ----
private val MetroCyan = Color(0xFF00ABA9)
private val MetroBlue = Color(0xFF1BA1E2)
private val MetroPurple = Color(0xFFA200FF)
private val MetroOrange = Color(0xFFF09609)
private val MetroMagenta = Color(0xFFD80073)
private val MetroDark = Color(0xFF1F1F1F)

private val OpenTopo = XYTileSource(
    "OpenTopoMap", 0, 17, 256, ".png",
    arrayOf(
        "https://a.tile.opentopomap.org/",
        "https://b.tile.opentopomap.org/",
        "https://c.tile.opentopomap.org/"
    ),
    "© OpenTopoMap (CC-BY-SA)"
)

private data class Place(val lat: Double, val lon: Double, val name: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
        }
        setContent { MetroMapApp() }
    }
}

@Composable
fun MetroMapApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var myLoc by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var topo by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    fun locate() {
        val m = mapView ?: return
        val overlay = myLoc ?: MyLocationNewOverlay(GpsMyLocationProvider(ctx), m).also {
            m.overlays.add(it)
            myLoc = it
        }
        overlay.enableMyLocation()
        val fix = overlay.myLocation
        if (fix != null) {
            m.controller.setZoom(16.0)
            m.controller.animateTo(fix)
            status = null
        } else {
            status = "определяю местоположение…"
            overlay.runOnFirstFix {
                m.post {
                    overlay.myLocation?.let {
                        m.controller.setZoom(16.0)
                        m.controller.animateTo(it)
                    }
                    status = null
                }
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) locate() else status = "нет доступа к геопозиции"
    }

    fun onLocateClick() {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            locate()
        } else {
            permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            status = "поиск…"
            val place = withContext(Dispatchers.IO) { geocode(q) }
            val m = mapView
            if (place == null || m == null) {
                status = "ничего не найдено"
            } else {
                val point = GeoPoint(place.lat, place.lon)
                marker?.let { m.overlays.remove(it) }
                val newMarker = Marker(m).apply {
                    position = point
                    title = place.name
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                m.overlays.add(newMarker)
                marker = newMarker
                m.controller.setZoom(15.0)
                m.controller.animateTo(point)
                m.invalidate()
                status = place.name
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onDetach()
        }
    }

    Box(Modifier.fillMaxSize().background(MetroDark)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(5.0)
                    controller.setCenter(GeoPoint(55.7558, 37.6173))
                    mapView = this
                }
            },
            update = { it.setTileSource(if (topo) OpenTopo else TileSourceFactory.MAPNIK) }
        )

        // ---- верх: поиск ----
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Light),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.weight(1f).height(52.dp).background(Color.White),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text("поиск места", color = Color.Gray, fontSize = 18.sp, fontWeight = FontWeight.Light)
                            }
                            inner()
                        }
                    }
                )
                Box(
                    Modifier.padding(start = 4.dp).size(52.dp).background(MetroCyan).clickable { search() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("→", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
                }
            }
            status?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                        .background(Color(0xE6000000)).padding(10.dp)
                )
            }
        }

        // ---- низ: плитки ----
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Tile("+", "приблизить", MetroBlue, Modifier.weight(1f)) { mapView?.controller?.zoomIn() }
            Tile("−", "отдалить", MetroPurple, Modifier.weight(1f)) { mapView?.controller?.zoomOut() }
            Tile("◎", "я здесь", MetroOrange, Modifier.weight(1f)) { onLocateClick() }
            Tile("▦", if (topo) "схема" else "рельеф", MetroMagenta, Modifier.weight(1f)) { topo = !topo }
        }
    }
}

@Composable
private fun Tile(glyph: String, label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(76.dp).background(color).clickable(onClick = onClick).padding(8.dp)) {
        Text(glyph, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light,
            modifier = Modifier.align(Alignment.TopStart))
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart))
    }
}

private fun geocode(q: String): Place? = try {
    val url = URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(q, "UTF-8"))
    val conn = url.openConnection() as HttpURLConnection
    conn.setRequestProperty("User-Agent", "MetroMapApp/1.0")
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
    if (arr.length() == 0) null else {
        val o = arr.getJSONObject(0)
        Place(o.getDouble("lat"), o.getDouble("lon"), o.getString("display_name"))
    }
} catch (e: Exception) {
    null
}
