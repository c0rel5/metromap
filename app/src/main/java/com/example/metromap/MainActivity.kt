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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.TextUnit
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
import kotlinx.coroutines.delay
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
import java.util.Locale
import kotlin.math.roundToInt

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

// ---- Акцентный цвет плиток ----
// MULTI = как раньше, у каждой плитки свой цвет; остальные красят все плитки в один цвет.
private enum class Accent(val key: String, val argb: Long?) {
    MULTI("multi", null),
    CYAN("cyan", 0xFF00ABA9),
    BLUE("blue", 0xFF1BA1E2),
    PURPLE("purple", 0xFFA200FF),
    ORANGE("orange", 0xFFF09609),
    MAGENTA("magenta", 0xFFD80073),
    GREEN("green", 0xFF60A917),
    RED("red", 0xFFE51400);

    companion object {
        fun fromKey(key: String?): Accent = values().firstOrNull { it.key == key } ?: MULTI
    }
}

// ---- Язык ----
private enum class Lang(val key: String) {
    RU("ru"), EN("en");

    companion object {
        fun fromKey(key: String?): Lang = values().firstOrNull { it.key == key } ?: RU
    }
}

private class Strings(
    val searchHint: String,
    val searching: String,
    val netError: String,
    val notFound: String,
    val showFail: String,
    val locating: String,
    val locFail: String,
    val noPerm: String,
    val zoomIn: String,
    val zoomOut: String,
    val locate: String,
    val mapLabel: String,
    val terrainLabel: String,
    val settings: String,
    val theme: String,
    val dark: String,
    val light: String,
    val system: String,
    val accent: String,
    val language: String,
    val liveTiles: String,
    val on: String,
    val off: String,
    val liveHint: String,
    val author: String,
    val tester: String,
    val zoomLevel: String,
    val center: String,
    val youHere: String,
    val tapHint: String,
    val layer: String,
)

private val RuStrings = Strings(
    searchHint = "поиск места",
    searching = "поиск…",
    netError = "ошибка сети, попробуйте ещё раз",
    notFound = "ничего не найдено",
    showFail = "не удалось показать результат",
    locating = "определяю местоположение…",
    locFail = "не удалось определить местоположение",
    noPerm = "нет доступа к геопозиции",
    zoomIn = "приблизить",
    zoomOut = "отдалить",
    locate = "я здесь",
    mapLabel = "схема",
    terrainLabel = "рельеф",
    settings = "настройки",
    theme = "тема",
    dark = "тёмная",
    light = "светлая",
    system = "как в системе",
    accent = "акцентный цвет",
    language = "язык",
    liveTiles = "живые плитки",
    on = "вкл",
    off = "выкл",
    liveHint = "плитки внизу переворачиваются и показывают масштаб, центр карты, твои координаты и слой. Функция в разработке.",
    author = "автор",
    tester = "тестер",
    zoomLevel = "масштаб",
    center = "центр",
    youHere = "ты здесь",
    tapHint = "нажми",
    layer = "слой",
)

private val EnStrings = Strings(
    searchHint = "search place",
    searching = "searching…",
    netError = "network error, try again",
    notFound = "nothing found",
    showFail = "couldn't show the result",
    locating = "locating…",
    locFail = "couldn't get your location",
    noPerm = "no location access",
    zoomIn = "zoom in",
    zoomOut = "zoom out",
    locate = "locate",
    mapLabel = "map",
    terrainLabel = "terrain",
    settings = "settings",
    theme = "theme",
    dark = "dark",
    light = "light",
    system = "system",
    accent = "accent color",
    language = "language",
    liveTiles = "live tiles",
    on = "on",
    off = "off",
    liveHint = "the bottom tiles flip to show zoom, map center, your coordinates and the map layer. Work in progress.",
    author = "author",
    tester = "tester",
    zoomLevel = "zoom",
    center = "center",
    youHere = "you are here",
    tapHint = "tap",
    layer = "layer",
)

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

private fun fmtCoord(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.3f\n%.3f", lat, lon)

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

    // ---- настройки (сохраняются) ----
    val prefs = remember { ctx.getSharedPreferences("metromap_prefs", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(themeFromKey(prefs.getString("theme", null))) }
    var accent by remember { mutableStateOf(Accent.fromKey(prefs.getString("accent", null))) }
    var lang by remember { mutableStateOf(Lang.fromKey(prefs.getString("lang", null))) }
    var liveTiles by remember { mutableStateOf(prefs.getBoolean("live", true)) }
    var showSettings by remember { mutableStateOf(false) }

    val s = if (lang == Lang.EN) EnStrings else RuStrings
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (isDark) DarkColors else LightColors
    val accentColor: Color? = accent.argb?.let { Color(it) }
    val accentMain: Color = accentColor ?: MetroCyan

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

    // данные для «живых плиток»
    var liveZoom by remember { mutableStateOf("–") }
    var liveCenter by remember { mutableStateOf("–") }
    var liveMe by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(liveTiles, mapView, myLoc) {
        while (liveTiles) {
            mapView?.let { m ->
                liveZoom = m.zoomLevelDouble.roundToInt().toString()
                val c = m.mapCenter
                liveCenter = fmtCoord(c.latitude, c.longitude)
            }
            liveMe = myLoc?.myLocation?.let { fmtCoord(it.latitude, it.longitude) }
            delay(1000)
        }
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
                status = s.locating
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
            status = s.locFail
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) locate() else status = s.noPerm
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
        val langKey = lang.key
        scope.launch {
            status = s.searching
            val result = withContext(Dispatchers.IO) { runCatching { geocode(q, langKey) } }
            val m = mapView
            val place = result.getOrNull()
            when {
                result.isFailure -> status = s.netError
                place == null || m == null -> status = s.notFound
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
                    status = s.showFail
                }
            }
        }
    }

    // onDetach вызывается только при реальном уничтожении карты (onRelease), а не при смене mapView.
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
                                Text(s.searchHint, color = colors.hint, fontSize = 18.sp, fontWeight = FontWeight.Light)
                            }
                            inner()
                        }
                    }
                )
                Box(
                    Modifier.padding(start = 4.dp).size(52.dp).background(accentMain).clickable { search() },
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

        // ---- низ: живые плитки ----
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LiveTile(
                glyph = "+", label = s.zoomIn,
                backGlyph = liveZoom, backLabel = s.zoomLevel,
                color = accentColor ?: MetroBlue, live = liveTiles, offsetMs = 0L,
                modifier = Modifier.weight(1f)
            ) { mapView?.controller?.zoomIn() }
            LiveTile(
                glyph = "−", label = s.zoomOut,
                backGlyph = liveCenter, backLabel = s.center, backGlyphSize = 14.sp,
                color = accentColor ?: MetroPurple, live = liveTiles, offsetMs = 600L,
                modifier = Modifier.weight(1f)
            ) { mapView?.controller?.zoomOut() }
            LiveTile(
                glyph = "◎", label = s.locate,
                backGlyph = liveMe ?: "?", backLabel = if (liveMe != null) s.youHere else s.tapHint,
                backGlyphSize = if (liveMe != null) 14.sp else 28.sp,
                color = accentColor ?: MetroOrange, live = liveTiles, offsetMs = 1200L,
                modifier = Modifier.weight(1f)
            ) { onLocateClick() }
            LiveTile(
                glyph = "▦", label = if (topo) s.mapLabel else s.terrainLabel,
                backGlyph = if (topo) "TOPO" else "OSM", backLabel = s.layer, backGlyphSize = 20.sp,
                color = accentColor ?: MetroMagenta, live = liveTiles, offsetMs = 1800L,
                modifier = Modifier.weight(1f)
            ) { topo = !topo }
        }

        // ---- настройки (поверх карты, карта остаётся живой) ----
        if (showSettings) {
            SettingsScreen(
                s = s,
                colors = colors,
                accentMain = accentMain,
                mode = themeMode,
                onMode = {
                    themeMode = it
                    prefs.edit().putString("theme", it.key).apply()
                },
                accent = accent,
                onAccent = {
                    accent = it
                    prefs.edit().putString("accent", it.key).apply()
                },
                lang = lang,
                onLang = {
                    lang = it
                    prefs.edit().putString("lang", it.key).apply()
                },
                live = liveTiles,
                onLive = {
                    liveTiles = it
                    prefs.edit().putBoolean("live", it).apply()
                },
                onBack = { showSettings = false }
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    s: Strings,
    colors: MetroColors,
    accentMain: Color,
    mode: ThemeMode,
    onMode: (ThemeMode) -> Unit,
    accent: Accent,
    onAccent: (Accent) -> Unit,
    lang: Lang,
    onLang: (Lang) -> Unit,
    live: Boolean,
    onLive: (Boolean) -> Unit,
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
    val optionFill = Color(0xFF2B2B2B)

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
            s.settings,
            color = colors.onBackground,
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        // ---- тема ----
        Text(s.theme, color = colors.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OptionTile(s.dark, Color(0xFF2B2B2B), Color.White, mode == ThemeMode.DARK, colors, accentMain, Modifier.weight(1f)) {
                onMode(ThemeMode.DARK)
            }
            OptionTile(s.light, Color(0xFFE6E6E6), Color(0xFF1F1F1F), mode == ThemeMode.LIGHT, colors, accentMain, Modifier.weight(1f)) {
                onMode(ThemeMode.LIGHT)
            }
            OptionTile(s.system, MetroBlue, Color.White, mode == ThemeMode.SYSTEM, colors, accentMain, Modifier.weight(1f)) {
                onMode(ThemeMode.SYSTEM)
            }
        }

        // ---- акцентный цвет плиток ----
        Spacer(Modifier.height(24.dp))
        Text(s.accent, color = colors.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        val row1 = listOf(Accent.MULTI, Accent.CYAN, Accent.BLUE, Accent.PURPLE)
        val row2 = listOf(Accent.ORANGE, Accent.MAGENTA, Accent.GREEN, Accent.RED)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row1.forEach { a ->
                AccentTile(a, accent == a, colors, Modifier.weight(1f)) { onAccent(a) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row2.forEach { a ->
                AccentTile(a, accent == a, colors, Modifier.weight(1f)) { onAccent(a) }
            }
        }

        // ---- язык ----
        Spacer(Modifier.height(24.dp))
        Text(s.language, color = colors.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OptionTile("Русский", optionFill, Color.White, lang == Lang.RU, colors, accentMain, Modifier.weight(1f)) {
                onLang(Lang.RU)
            }
            OptionTile("English", optionFill, Color.White, lang == Lang.EN, colors, accentMain, Modifier.weight(1f)) {
                onLang(Lang.EN)
            }
        }

        // ---- живые плитки (beta) ----
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(s.liveTiles, color = colors.muted, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            BetaBadge(accentMain)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OptionTile(s.on, optionFill, Color.White, live, colors, accentMain, Modifier.weight(1f)) { onLive(true) }
            OptionTile(s.off, optionFill, Color.White, !live, colors, accentMain, Modifier.weight(1f)) { onLive(false) }
        }
        Text(
            s.liveHint,
            color = colors.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(40.dp))

        // ---- конец настроек: ник и гифка ----
        Text(s.author, color = colors.muted, fontSize = 14.sp)
        Text(
            "corei5",
            color = accentMain,
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
        Text(s.tester, color = colors.muted, fontSize = 14.sp)
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
private fun BetaBadge(color: Color) {
    Text(
        "BETA",
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun OptionTile(
    label: String,
    fill: Color,
    textColor: Color,
    selected: Boolean,
    colors: MetroColors,
    accentMain: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(76.dp)
            .background(fill)
            .then(if (selected) Modifier.border(3.dp, accentMain) else Modifier.border(1.dp, colors.muted))
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
private fun AccentTile(
    accent: Accent,
    selected: Boolean,
    colors: MetroColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(48.dp)
            .then(if (selected) Modifier.border(3.dp, colors.onBackground) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val argb = accent.argb
        if (argb == null) {
            // «разноцветные»: четыре полоски в цветах плиток
            Row(Modifier.fillMaxSize()) {
                listOf(MetroBlue, MetroPurple, MetroOrange, MetroMagenta).forEach { c ->
                    Box(Modifier.weight(1f).fillMaxSize().background(c))
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color(argb)))
        }
        if (selected) {
            Text("✓", color = Color.White, fontSize = 20.sp)
        }
    }
}

// Живая плитка (beta): обычная лицевая сторона, периодически переворачивается и показывает данные.
@Composable
private fun LiveTile(
    glyph: String,
    label: String,
    backGlyph: String,
    backLabel: String,
    color: Color,
    live: Boolean,
    offsetMs: Long,
    modifier: Modifier = Modifier,
    backGlyphSize: TextUnit = 28.sp,
    onClick: () -> Unit,
) {
    var flipped by remember { mutableStateOf(false) }
    LaunchedEffect(live) {
        if (!live) {
            flipped = false
        } else {
            delay(offsetMs)
            while (true) {
                delay(4000)
                flipped = true
                delay(3000)
                flipped = false
            }
        }
    }
    val rot by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(500),
        label = "tileFlip"
    )

    Box(
        modifier.height(76.dp)
            .graphicsLayer {
                rotationX = rot
                cameraDistance = 14f * density
            }
            .background(color)
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        if (rot <= 90f) {
            Text(
                glyph, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.TopStart)
            )
            Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart))
        } else {
            Box(Modifier.fillMaxSize().graphicsLayer { rotationX = 180f }) {
                Text(
                    backGlyph, color = Color.White, fontSize = backGlyphSize, lineHeight = backGlyphSize,
                    fontWeight = FontWeight.Light, maxLines = 2,
                    modifier = Modifier.align(Alignment.TopStart)
                )
                Text(backLabel, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

// Бросает исключение при сетевой ошибке, возвращает null если ничего не найдено.
private fun geocode(q: String, lang: String): Place? {
    val url = URL(
        "https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=" + lang +
            "&q=" + URLEncoder.encode(q, "UTF-8")
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
