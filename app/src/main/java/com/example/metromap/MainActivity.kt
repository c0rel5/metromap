package com.example.metromap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
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
import java.io.File
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

private const val USER_AGENT = "MetroMapApp/1.0 (com.example.metromap)"

// ---- Темы ----
private enum class ThemeMode(val key: String) {
    DARK("dark"), LIGHT("light"), SYSTEM("system")
}

private fun themeFromKey(key: String?): ThemeMode =
    ThemeMode.values().firstOrNull { it.key == key } ?: ThemeMode.DARK // по умолчанию тёмная

private class MetroColors(
    val background: Color,
    val onBackground: Color,
    val muted: Color,
    val inputBg: Color,
    val inputText: Color,
    val hint: Color,
    val statusBg: Color,
    val statusText: Color,
)

private val DarkColors = MetroColors(
    background = Color(0xFF121212),
    onBackground = Color.White,
    muted = Color(0xFF9E9E9E),
    inputBg = Color(0xFF2B2B2B),
    inputText = Color.White,
    hint = Color(0xFF8A8A8A),
    statusBg = Color(0xE6000000),
    statusText = Color.White,
)

private val LightColors = MetroColors(
    background = Color(0xFFF2F2F2),
    onBackground = Color(0xFF1F1F1F),
    muted = Color(0xFF666666),
    inputBg = Color.White,
    inputText = Color.Black,
    hint = Color.Gray,
    statusBg = Color(0xE6FFFFFF),
    statusText = Color(0xFF1F1F1F),
)

// Тёмная карта: invert + hue-rotate(180°). Белое -> чёрное, вода остаётся синей, парки зелёными.
private val DarkTilesFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Configuration.getInstance().apply {
            userAgentValue = USER_AGENT
            osmdroidBasePath = File(filesDir, "osmdroid").apply { mkdirs() }
            osmdroidTileCache = File(cacheDir, "osmdroid_tiles").apply { mkdirs() }
        }
        setContent { MetroMapApp() }
    }
}

@Composable
fun MetroMapApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    // ---- тема (по умолчанию тёмная, выбор сохраняется) ----
    val prefs = remember { ctx.getSharedPreferences("metromap_prefs", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(themeFromKey(prefs.getString("theme", null))) }
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (isDark) DarkColors else LightColors
    var showSettings by remember { mutableStateOf(false) }

    // иконки статус-бара и навигации под тему
    SideEffect {
        val bars = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        (ctx as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var myLoc by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var topo by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

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
        } catch (e: Exception) {
            status = "не удалось определить местоположение"
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
        keyboard?.hide()
        focus.clearFocus()
        scope.launch {
            status = "поиск…"
            val result = withContext(Dispatchers.IO) { runCatching { geocode(q) } }
            val m = mapView
            val place = result.getOrNull()
            when {
                result.isFailure -> status = "ошибка сети, попробуйте ещё раз"
                place == null || m == null -> status = "ничего не найдено"
                else -> try {
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
                } catch (e: Exception) {
                    status = "не удалось показать результат"
                }
            }
        }
    }

    // ВАЖНО: раньше в onDispose вызывался mapView?.onDetach(). Когда mapView менялся
    // с null на созданную карту, старый эффект «умирал» и отсоединял уже новую карту
    // (tile-провайдер останавливался) - карта оставалась пустой. Теперь view берётся
    // в локальную переменную, а onDetach вызывается только при реальном уничтожении (onRelease).
    DisposableEffect(lifecycleOwner, mapView) {
        val view = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            update = { view ->
                val wanted = if (topo) OpenTopo else TileSourceFactory.MAPNIK
                if (view.tileProvider.tileSource.name() != wanted.name()) {
                    view.setTileSource(wanted)
                }
                view.overlayManager.tilesOverlay.apply {
                    setColorFilter(if (isDark) DarkTilesFilter else null)
                    // цвет ещё не загруженных плиток: в тёмной теме это цвет «суши» Mapnik,
                    // после фильтра он сливается с картой (без коричневых квадратов)
                    val loading = if (isDark) 0xFFF2EFE9.toInt() else android.graphics.Color.rgb(216, 208, 208)
                    setLoadingBackgroundColor(loading)
                    setLoadingLineColor(loading)
                }
                view.invalidate()
            },
            onRelease = { it.onDetach() }
        )

        // ---- верх: поиск + настройки ----
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.inputText, fontSize = 18.sp, fontWeight = FontWeight.Light),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.weight(1f).height(52.dp).background(colors.inputBg),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text("поиск места", color = colors.hint, fontSize = 18.sp, fontWeight = FontWeight.Light)
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
                    Modifier.padding(start = 4.dp).size(52.dp).background(MetroDark).clickable { showSettings = true },
                    contentAlignment = Alignment.Center
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(3) { Box(Modifier.size(22.dp, 3.dp).background(Color.White)) }
                    }
                }
            }
            status?.let {
                Text(
                    it,
                    color = colors.statusText,
                    fontSize = 13.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                        .background(colors.statusBg).padding(10.dp)
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

        // ---- настройки (поверх карты, карта остаётся живой) ----
        if (showSettings) {
            SettingsScreen(
                colors = colors,
                mode = themeMode,
                onMode = {
                    themeMode = it
                    prefs.edit().putString("theme", it.key).apply()
                },
                onBack = { showSettings = false }
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    colors: MetroColors,
    mode: ThemeMode,
    onMode: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)

    val gifLoader = remember {
        ImageLoader.Builder(ctx)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()
    }

    Column(
        Modifier.fillMaxSize()
            .background(colors.background)
            .pointerInput(Unit) { detectTapGestures { } } // не пускаем касания к карте под экраном
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box(
            Modifier.size(48.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.CenterStart
        ) {
            Text("←", color = colors.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Light)
        }
        Text(
            "настройки",
            color = colors.onBackground,
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        Text("тема", color = colors.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeTile("тёмная", Color(0xFF2B2B2B), Color.White, mode == ThemeMode.DARK, colors, Modifier.weight(1f)) {
                onMode(ThemeMode.DARK)
            }
            ThemeTile("светлая", Color(0xFFE6E6E6), Color(0xFF1F1F1F), mode == ThemeMode.LIGHT, colors, Modifier.weight(1f)) {
                onMode(ThemeMode.LIGHT)
            }
            ThemeTile("как в системе", MetroBlue, Color.White, mode == ThemeMode.SYSTEM, colors, Modifier.weight(1f)) {
                onMode(ThemeMode.SYSTEM)
            }
        }

        Spacer(Modifier.height(40.dp))

        // ---- конец настроек: ник и гифка ----
        Text("автор", color = colors.muted, fontSize = 14.sp)
        Text(
            "corei5",
            color = MetroCyan,
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        AsyncImage(
            model = R.raw.nekopara_chocola,
            imageLoader = gifLoader,
            contentDescription = "corei5",
            modifier = Modifier.fillMaxWidth().aspectRatio(480f / 272f)
        )

        Spacer(Modifier.height(32.dp))

        // ---- тестер ----
        Text("тестер", color = colors.muted, fontSize = 14.sp)
        Text(
            "meoww",
            color = MetroMagenta,
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        AsyncImage(
            model = R.raw.nekopara_vanilla,
            imageLoader = gifLoader,
            contentDescription = "meoww",
            modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(296f / 360f)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ThemeTile(
    label: String,
    fill: Color,
    textColor: Color,
    selected: Boolean,
    colors: MetroColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(76.dp)
            .background(fill)
            .then(if (selected) Modifier.border(3.dp, MetroCyan) else Modifier.border(1.dp, colors.muted))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        if (selected) {
            Text("✓", color = textColor, fontSize = 22.sp, modifier = Modifier.align(Alignment.TopStart))
        }
        Text(label, color = textColor, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomStart))
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

// Бросает исключение при сетевой ошибке, возвращает null если ничего не найдено.
private fun geocode(q: String): Place? {
    val url = URL(
        "https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=ru&q=" +
            URLEncoder.encode(q, "UTF-8")
    )
    val conn = url.openConnection() as HttpURLConnection
    try {
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw java.io.IOException("HTTP " + conn.responseCode)
        }
        val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return Place(o.getDouble("lat"), o.getDouble("lon"), o.optString("display_name", q))
    } finally {
        conn.disconnect()
    }
}
