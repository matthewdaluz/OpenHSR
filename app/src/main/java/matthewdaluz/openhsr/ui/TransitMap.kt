package matthewdaluz.openhsr.ui

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import matthewdaluz.openhsr.MapBus
import matthewdaluz.openhsr.MapFocus
import matthewdaluz.openhsr.data.Coordinates
import matthewdaluz.openhsr.data.Stop
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textPadding
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val BASEMAP = "https://tiles.openfreemap.org/styles/positron"
private const val FALLBACK = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#e6eee9"}}]}"""

private const val STOPS = "hsr-stops"
private const val SELECTED = "hsr-selected"
private const val BUSES = "hsr-buses"
private const val BUS_LABELS = "hsr-bus-labels"
private const val LOCATION = "hsr-location"

@Composable
fun TransitMap(
    stops: List<Stop>,
    selected: Stop?,
    buses: List<MapBus>,
    location: Coordinates?,
    focus: MapFocus?,
    bottomPadding: Dp,
    topPadding: Dp,
    reload: Int,
    onBasemapStatus: (available: Boolean) -> Unit,
    onStop: (String) -> Unit,
    onBus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(context) {
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)
            .logoEnabled(false).attributionEnabled(false))
    }
    val stopClick by rememberUpdatedState(onStop)
    val busClick by rememberUpdatedState(onBus)
    val basemapStatus by rememberUpdatedState(onBasemapStatus)
    val density = LocalDensity.current
    val tapRadius by rememberUpdatedState(with(density) { 12.dp.toPx() })
    var map by remember(view) { mutableStateOf<MapLibreMap?>(null) }
    var style by remember(view) { mutableStateOf<Style?>(null) }
    var usingFallback by remember(view) { mutableStateOf(false) }

    DisposableEffect(view, lifecycle) {
        view.onCreate(null)
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { view.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { view.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { view.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { view.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        val failure = MapView.OnDidFailLoadingMapListener {
            val controller = map
            if (!usingFallback && controller != null) {
                usingFallback = true
                style = null
                basemapStatus(false)
                controller.setStyle(Style.Builder().fromJson(FALLBACK)) { fallback ->
                    fallback.addTransitLayers(withLabels = false)
                    style = fallback
                }
            }
        }
        view.addOnDidFailLoadingMapListener(failure)
        view.getMapAsync { controller ->
            controller.uiSettings.isTiltGesturesEnabled = false
            controller.uiSettings.isRotateGesturesEnabled = false
            controller.cameraPosition = CameraPosition.Builder().target(LatLng(43.2566, -79.8694)).zoom(13.2).build()
            controller.addOnMapClickListener { position ->
                val screen = controller.projection.toScreenLocation(position)
                val area = RectF(screen.x - tapRadius, screen.y - tapRadius, screen.x + tapRadius, screen.y + tapRadius)
                // Prefer the closest marker when opposite-direction stops are next to each other.
                val marker = controller.queryRenderedFeatures(area, BUSES, SELECTED, STOPS)
                    .mapNotNull { feature ->
                        val point = feature.geometry() as? Point ?: return@mapNotNull null
                        val projected = controller.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                        val dx = projected.x - screen.x
                        val dy = projected.y - screen.y
                        feature to dx * dx + dy * dy
                    }.minByOrNull { it.second }?.first
                when {
                    marker == null -> false
                    marker.hasProperty("stop") -> { stopClick(marker.getStringProperty("stop")); true }
                    marker.hasProperty("bus") -> { busClick(marker.getStringProperty("bus")); true }
                    else -> false
                }
            }
            map = controller
        }
        onDispose {
            lifecycle.removeObserver(observer)
            view.removeOnDidFailLoadingMapListener(failure)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(map, reload) {
        val controller = map ?: return@LaunchedEffect
        usingFallback = false
        style = null
        controller.setStyle(Style.Builder().fromUri(BASEMAP)) { loaded ->
            loaded.addTransitLayers(withLabels = true)
            style = loaded
            basemapStatus(true)
        }
    }

    val stopFeatures = remember(stops) {
        features(stops.map { stop -> feature(Coordinates(stop.latitude, stop.longitude), "stop", stop.id) })
    }
    val selectedFeature = remember(selected) {
        features(listOfNotNull(selected?.let { feature(Coordinates(it.latitude, it.longitude), "stop", it.id) }))
    }
    val busFeatures = remember(buses) {
        features(buses.map { bus ->
            feature(bus.point, "bus", bus.id).apply { addStringProperty("route", bus.route.orEmpty()) }
        })
    }
    val locationFeature = remember(location) {
        features(listOfNotNull(location?.let { feature(it, "user", "You") }))
    }
    LaunchedEffect(style, stopFeatures) { style?.getSourceAs<GeoJsonSource>(STOPS)?.setGeoJson(stopFeatures) }
    LaunchedEffect(style, selectedFeature) { style?.getSourceAs<GeoJsonSource>(SELECTED)?.setGeoJson(selectedFeature) }
    LaunchedEffect(style, busFeatures) { style?.getSourceAs<GeoJsonSource>(BUSES)?.setGeoJson(busFeatures) }
    LaunchedEffect(style, locationFeature) { style?.getSourceAs<GeoJsonSource>(LOCATION)?.setGeoJson(locationFeature) }

    val bottom = with(density) { bottomPadding.roundToPx() }
    val top = with(density) { topPadding.roundToPx() }
    LaunchedEffect(map, top, bottom) {
        map?.moveCamera(CameraUpdateFactory.paddingTo(0.0, top.toDouble(), 0.0, bottom.toDouble()))
    }

    LaunchedEffect(map, focus) {
        val controller = map ?: return@LaunchedEffect
        val target = focus?.point ?: return@LaunchedEffect
        controller.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), maxOf(controller.cameraPosition.zoom, 15.2)),
            300,
        )
    }

    AndroidView(factory = { view }, modifier = modifier)
}

private fun Style.addTransitLayers(withLabels: Boolean) {
    addSource(GeoJsonSource(STOPS, features(emptyList())))
    addSource(GeoJsonSource(SELECTED, features(emptyList())))
    addSource(GeoJsonSource(BUSES, features(emptyList())))
    addSource(GeoJsonSource(LOCATION, features(emptyList())))
    addLayer(CircleLayer(STOPS, STOPS).apply {
        minZoom = 12f
        setProperties(circleColor("#176657"), circleRadius(4.5f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1.5f))
    })
    addLayer(CircleLayer(SELECTED, SELECTED).withProperties(
        circleColor("#087262"), circleRadius(9f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f),
    ))
    addLayer(CircleLayer(LOCATION, LOCATION).withProperties(
        circleColor("#235AC6"), circleRadius(6.5f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f),
    ))
    addLayer(CircleLayer(BUSES, BUSES).apply {
        minZoom = 11f
        setProperties(circleColor("#654277"), circleRadius(12f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1.5f))
    })
    if (withLabels) {
        addLayer(SymbolLayer(BUS_LABELS, BUSES).apply {
            minZoom = 11f
            setProperties(textField(get("route")), textFont(arrayOf("Noto Sans Bold")), textSize(10f),
                textColor("#FFFFFF"), textPadding(4f))
        })
    }
}

private fun features(items: List<Feature>): FeatureCollection = FeatureCollection.fromFeatures(items)

private fun feature(coordinates: Coordinates, key: String, value: String): Feature =
    Feature.fromGeometry(Point.fromLngLat(coordinates.longitude, coordinates.latitude)).apply {
        addStringProperty(key, value)
    }
