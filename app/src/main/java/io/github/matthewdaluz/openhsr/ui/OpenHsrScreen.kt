package io.github.matthewdaluz.openhsr.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.matthewdaluz.openhsr.LocationStatus
import io.github.matthewdaluz.openhsr.TransitViewModel
import io.github.matthewdaluz.openhsr.data.Stop
import io.github.matthewdaluz.openhsr.data.searchStops
import io.github.matthewdaluz.openhsr.hasLocationPermission
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val openHsrColors = lightColorScheme(
    primary = Color(0xFF075B50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBEEE4),
    onPrimaryContainer = Color(0xFF003C33),
    secondary = Color(0xFF654277),
    secondaryContainer = Color(0xFFEFDEF9),
    onSecondaryContainer = Color(0xFF331444),
    surface = Color(0xFFFAFCFB),
    surfaceContainer = Color(0xFFF0F5F2),
    surfaceVariant = Color(0xFFE3EBE6),
    onSurface = Color(0xFF162621),
    onSurfaceVariant = Color(0xFF40534B),
    outline = Color(0xFF687C73),
    outlineVariant = Color(0xFFD0DED6),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenHsrScreen(viewModel: TransitViewModel) {
    val stops by viewModel.stops.collectAsStateWithLifecycle()
    val buses by viewModel.buses.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val boards by viewModel.boards.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()
    val location by viewModel.location.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val refresh by viewModel.refreshStatus.collectAsStateWithLifecycle()
    val now by viewModel.time.collectAsStateWithLifecycle()
    val live by viewModel.liveDepartures.collectAsStateWithLifecycle()
    val mapFocus by viewModel.mapFocus.collectAsStateWithLifecycle()
    val stop = remember(stops, selection.stopId) { stops.firstOrNull { it.id == selection.stopId } }

    var query by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf(false) }
    var busId by remember { mutableStateOf<String?>(null) }
    val bus = remember(buses, busId) { buses.firstOrNull { it.id == busId } }
    LaunchedEffect(bus) { if (bus == null) busId = null }
    var basemapAvailable by remember { mutableStateOf(true) }
    var mapReload by remember { mutableIntStateOf(0) }
    val search = remember(stops, query) { searchStops(stops, query) }

    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val scaffold = rememberBottomSheetScaffoldState()
    val sheet = scaffold.bottomSheetState
    fun expand() { scope.launch { sheet.expand() } }
    fun nearMe() {
        query = ""
        focus.clearFocus()
        expand()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.hasLocationPermission()) viewModel.locate() else viewModel.locationDenied()
    }

    BackHandler(query.isNotEmpty() || selection.stopId != null || sheet.currentValue == SheetValue.Expanded) {
        when {
            query.isNotEmpty() -> { query = ""; focus.clearFocus() }
            selection.stopId != null -> viewModel.showNearby()
            else -> scope.launch { sheet.partialExpand() }
        }
    }

    MaterialTheme(colorScheme = openHsrColors) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().clipToBounds(),
        ) {
            val screenHeight = maxHeight
            val peek = 162.dp.coerceAtMost(screenHeight)
            val sheetHeight = (screenHeight * 0.64f).coerceAtLeast(230.dp)
                .coerceAtMost((screenHeight - 32.dp).coerceAtLeast(0.dp))
            val mapBottom = if (sheet.targetValue == SheetValue.Expanded) sheetHeight + 24.dp else peek
            val selectStop = { selected: Stop ->
                query = ""
                focus.clearFocus()
                viewModel.selectStop(selected)
                expand()
            }
            BottomSheetScaffold(
                scaffoldState = scaffold,
                sheetPeekHeight = peek,
                sheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                sheetContainerColor = MaterialTheme.colorScheme.surface,
                sheetShadowElevation = 8.dp,
                sheetContent = {
                    DepartureSheet(
                        height = sheetHeight,
                        stop = stop,
                        selection = selection,
                        boards = boards,
                        nearby = nearby,
                        location = location,
                        info = info,
                        refresh = refresh,
                        now = now,
                        live = live,
                        query = query,
                        results = search,
                        onQuery = { query = it; expand() },
                        onSearchFocus = ::expand,
                        onNearMe = {
                            nearMe()
                            if (context.hasLocationPermission()) viewModel.locate() else permission.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        },
                        onStop = selectStop,
                        onBack = viewModel::showNearby,
                        onDate = viewModel::selectDate,
                        onRetry = viewModel::retrySchedule,
                    )
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    TransitMap(
                        stops = stops,
                        selected = stop,
                        buses = buses,
                        location = (location as? LocationStatus.Located)?.point,
                        focus = mapFocus,
                        bottomPadding = mapBottom,
                        topPadding = 42.dp,
                        reload = mapReload,
                        onBasemapStatus = { basemapAvailable = it },
                        onStop = { id -> stops.firstOrNull { it.id == id }?.let(selectStop) },
                        onBus = { busId = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 3.dp) {
                            Text(
                                "OpenHSR",
                                Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 3.dp) {
                            TextButton(onClick = { about = true }) { Text("About") }
                        }
                    }
                    if (!basemapAvailable) {
                        Surface(
                            Modifier.align(Alignment.TopCenter).padding(top = 68.dp),
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = 3.dp,
                        ) {
                            TextButton(onClick = { mapReload++ }) { Text("Basemap unavailable · Retry") }
                        }
                    }
                    Surface(
                        modifier = Modifier.offset {
                            val sheetTop = runCatching { sheet.requireOffset() }
                                .getOrDefault((screenHeight - peek).toPx())
                            IntOffset(12.dp.roundToPx(), (sheetTop - 36.dp.toPx()).roundToInt()
                                .coerceAtLeast(64.dp.roundToPx()))
                        },
                        onClick = { about = true },
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            "© OpenStreetMap contributors · © OpenMapTiles",
                            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (about) AboutDialog { about = false }
        bus?.let { selected ->
            AlertDialog(
                onDismissRequest = { busId = null },
                title = { Text(selected.route?.let { "Route $it" } ?: "HSR bus") },
                text = { Text(selected.headsign ?: "Live vehicle position") },
                confirmButton = { TextButton(onClick = { busId = null }) { Text("Close") } },
            )
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About OpenHSR") },
        text = {
            Column {
                Text("OpenHSR is an independent, open-source app for Hamilton transit riders.")
                Text(
                    "Contains public sector Data made available under the City of Hamilton’s Open Data Licence",
                    Modifier.padding(top = 14.dp),
                )
                TextButton({ uri.openUri("https://www.hamilton.ca/city-council/data-maps/open-data/open-data-licence-terms-and-conditions") }) {
                    Text("Hamilton’s Open Data Licence")
                }
                TextButton({ uri.openUri("https://www.openstreetmap.org/copyright") }) { Text("OpenStreetMap contributors") }
                TextButton({ uri.openUri("https://openmaptiles.org/") }) { Text("OpenMapTiles") }
                TextButton({ uri.openUri("https://openfreemap.org/") }) { Text("OpenFreeMap") }
                TextButton({ uri.openUri("https://github.com/matthewdaluz/OpenHSR") }) { Text("Source code and GPLv3") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
