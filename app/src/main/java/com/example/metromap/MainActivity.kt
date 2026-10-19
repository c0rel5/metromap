package com.example.metromap

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
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
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.graphics.Color as AColor

// ---- Metro palette ----
private val MetroCyan = Color(0xFF00ABA9)
private val MetroBlue = Color(0xFF1BA1E2)
private val MetroPurple = Color(0xFFA200FF)
private val MetroOrange = Color(0xFFF09609)
private val MetroMagenta = Color(0xFFD80073)

// ---- Themes ----
private enum class ThemeMode(val label: String) {
    SYSTEM("системная"), LIGHT("светлая"), DARK("тёмная"), AMOLED("amoled")
}

private class Palette(
    val field: Color,
    val fieldText: Color,
    val hint: Color,
    val border: Color,
    val status: Color,
    val dark: Boolean
)

private val LightPalette = Palette(Color.White, Color.Black, Color(0xFF808080), Color.Transparent, Color(0xE6000000), false)
private val DarkPalette = Palette(Color(0xFF2B2B2B), Color.White, Color(0xFF9A9A9A), Color.Transparent, Color(0xF2121212), true)
private val AmoledPalette = Palette(Color.Black, Color.White, Color(0xFF8A8A8A), Color(0xFF5A5A5A), Color.Black, true)

// Инверсия цветов тайлов = тёмная карта
private val InvertFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

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

// Метка в стиле Metro: квадрат с белой рамкой (без Marker и его info-окна)
private class PinOverlay(private val point: GeoPoint, private val density: Float) : Overlay() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.parseColor("#00ABA9") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE }
    private val pt = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        mapView.projection.toPixels(point, pt)
        val half = 14f * density
        val b = 3f * density
        canvas.drawRect(pt.x - half - b, pt.y - half - b, pt.x + half + b, pt.y + half + b, edge)
        canvas.drawRect(pt.x - half, pt.y - half, pt.x + half, pt.y + half, fill)
    }
}

private fun logCrash(file: File, e: Throwable) {
    try {
        file.writeText(Log.getStackTraceString(e))
    } catch (_: Throwable) {
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Любая необработанная ошибка сохраняется и показывается при следующем запуске
        val crashFile = File(filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            logCrash(crashFile, e)
            previous?.uncaughtException(t, e)
        }

        val osm = Configuration.getInstance()
        osm.load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        // OSM блокирует запросы тайлов без нормального User-Agent
        osm.userAgentValue = "MetroMap/1.0 (https://github.com/c0rel5/metromap)"
        osm.osmdroidBasePath = File(filesDir, "osmdroid").apply { mkdirs() }
        osm.osmdroidTileCache = File(cacheDir, "osmdroid-tiles").apply { mkdirs() }

        setContent { MetroMapApp() }
    }
}

@Composable
fun MetroMapApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val crashFile = remember { File(ctx.filesDir, "crash.txt") }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var myLoc by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var pin by remember { mutableStateOf<PinOverlay?>(null) }
    var topo by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var crashText by remember {
        mutableStateOf(if (crashFile.exists()) runCatching { crashFile.readText() }.getOrNull() else null)
    }
    var themeMode by remember {
        mutableStateOf(
            ThemeMode.values().firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.SYSTEM
        )
    }

    val systemDark = isSystemInDarkTheme()
    val palette = when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkPalette else LightPalette
        ThemeMode.LIGHT -> LightPalette
        ThemeMode.DARK -> DarkPalette
        ThemeMode.AMOLED -> AmoledPalette
    }
    val dark = palette.dark
    val isTopo = topo

    // иконки статус-бара под тему
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }

    fun nextTheme() {
        val all = ThemeMode.values()
        themeMode = all[(themeMode.ordinal + 1) % all.size]
        prefs.edit().putString("theme", themeMode.name).apply()
        status = "тема: ${themeMode.label}"
    }

    fun locate() {
        val m = mapView ?: return
        try {
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
        } catch (e: Throwable) {
            logCrash(crashFile, e)
            status = "ошибка геолокации: ${e.javaClass.simpleName}"
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
            try {
                val place = withContext(Dispatchers.IO) { geocode(q) }
                val m = mapView
                if (place == null || m == null) {
                    status = "ничего не найдено (проверь интернет)"
                } else {
                    val point = GeoPoint(place.lat, place.lon)
                    pin?.let { m.overlays.remove(it) }
                    val newPin = PinOverlay(point, ctx.resources.displayMetrics.density)
                    m.overlays.add(newPin)
                    pin = newPin
                    m.controller.setZoom(15.0)
                    m.controller.animateTo(point)
                    m.invalidate()
                    status = place.name
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logCrash(crashFile, e)
                status = "ошибка поиска: ${e.javaClass.simpleName}"
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

    Box(Modifier.fillMaxSize().background(if (themeMode == ThemeMode.AMOLED) Color.Black else Color(0xFF1F1F1F))) {
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
            update = { map ->
                map.setTileSource(if (isTopo) OpenTopo else TileSourceFactory.MAPNIK)
                val tiles = map.overlayManager.tilesOverlay
                tiles.setColorFilter(if (dark) InvertFilter else null)
                tiles.setLoadingBackgroundColor(if (dark) AColor.BLACK else 0xFFE6E6E6.toInt())
                tiles.setLoadingLineColor(if (dark) 0xFF222222.toInt() else 0xFFD0D0D0.toInt())
                map.invalidate()
            }
        )

        // ---- верх: поиск + тема ----
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = palette.fieldText, fontSize = 18.sp, fontWeight = FontWeight.Light),
                    cursorBrush = SolidColor(palette.fieldText),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.weight(1f).height(52.dp)
                        .background(palette.field).border(2.dp, palette.border),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text("поиск места", color = palette.hint, fontSize = 18.sp, fontWeight = FontWeight.Light)
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
                Box(
                    Modifier.padding(start = 4.dp).size(52.dp).background(MetroPurple).clickable { nextTheme() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("◐", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
                }
            }
            status?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                        .background(palette.status).padding(10.dp)
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

        // ---- экран ошибки (если приложение раньше падало) ----
        crashText?.let { text ->
            Column(
                Modifier.fillMaxSize().background(Color.Black)
                    .statusBarsPadding().navigationBarsPadding().padding(12.dp)
            ) {
                Text("приложение закрылось с ошибкой", color = MetroOrange, fontSize = 18.sp)
                Text(
                    text,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tile("□", "копировать", MetroBlue, Modifier.weight(1f)) {
                        clipboard.setText(AnnotatedString(text))
                    }
                    Tile("×", "закрыть", MetroMagenta, Modifier.weight(1f)) {
                        crashFile.delete()
                        crashText = null
                    }
                }
            }
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

private fun geocode(q: String): Place? {
    val url = URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(q, "UTF-8"))
    val conn = url.openConnection() as HttpURLConnection
    try {
        conn.setRequestProperty("User-Agent", "MetroMap/1.0 (https://github.com/c0rel5/metromap)")
        conn.setRequestProperty("Accept-Language", "ru")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        if (conn.responseCode != 200) return null
        val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return Place(o.getDouble("lat"), o.getDouble("lon"), o.getString("display_name"))
    } catch (e: java.io.IOException) {
        return null
    } finally {
        conn.disconnect()
    }
}
