package matthewdaluz.openhsr

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import matthewdaluz.openhsr.data.Coordinates
import matthewdaluz.openhsr.data.Departure
import matthewdaluz.openhsr.data.HamiltonFeed
import matthewdaluz.openhsr.data.NearbyStop
import matthewdaluz.openhsr.data.ScheduleDatabase
import matthewdaluz.openhsr.data.Stop
import matthewdaluz.openhsr.data.TripSnapshot
import matthewdaluz.openhsr.data.VehicleSnapshot
import matthewdaluz.openhsr.data.nearbyStops
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StopSelection(val stopId: String? = null, val date: LocalDate? = null)

enum class RefreshStatus { IDLE, LOADING, FAILED }

sealed interface LocationStatus {
    data object Idle : LocationStatus
    data object Finding : LocationStatus
    data object Denied : LocationStatus
    data object Unavailable : LocationStatus
    data class Located(val point: Coordinates) : LocationStatus
}

data class MapFocus(val point: Coordinates, val sequence: Int)

data class MapBus(val id: String, val point: Coordinates, val route: String?, val headsign: String?)

data class Boards(
    val selection: StopSelection = StopSelection(),
    val hasSelectedBoard: Boolean = false,
    val selected: List<Departure> = emptyList(),
    val nearby: Map<String, Departure?> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TransitViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val clock = Clock.systemUTC()
    private val database = Room.databaseBuilder(application, ScheduleDatabase::class.java, "hsr-schedule.db").build()
    private val feed = HamiltonFeed(database, application.cacheDir, clock)
    private val dao = database.schedule()
    private val subscribed = SharingStarted.WhileSubscribed(5_000)

    val info = dao.observeInfo().stateIn(viewModelScope, subscribed, null)
    val stops = dao.observeStops().stateIn(viewModelScope, subscribed, emptyList())

    private val _selection = MutableStateFlow(
        StopSelection(savedState.get<String>("stopId"), savedState.get<String>("date")?.let(LocalDate::parse)),
    )
    val selection = _selection.asStateFlow()

    private val _refreshStatus = MutableStateFlow(RefreshStatus.IDLE)
    val refreshStatus = _refreshStatus.asStateFlow()
    private val refreshLock = Mutex()

    private val _location = MutableStateFlow<LocationStatus>(LocationStatus.Idle)
    val location = _location.asStateFlow()
    private var locationJob: Job? = null

    private val _mapFocus = MutableStateFlow<MapFocus?>(null)
    val mapFocus = _mapFocus.asStateFlow()

    private val _time = MutableStateFlow(clock.instant())
    val time = _time.asStateFlow()
    private val tripUpdates = MutableStateFlow<TripSnapshot?>(null)
    private val vehiclePositions = MutableStateFlow<VehicleSnapshot?>(null)

    val liveDepartures = combine(time, tripUpdates) { now, live -> live?.isFresh(now) == true }
        .stateIn(viewModelScope, subscribed, false)

    val nearby = combine(stops, location) { allStops, state ->
        if (state is LocationStatus.Located) nearbyStops(state.point, allStops) else emptyList()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, subscribed, emptyList())

    private val board = combine(
        selection.map { it.stopId }.distinctUntilChanged(),
        nearby.map { list -> list.map { it.stop.id } }.distinctUntilChanged(),
        info.map { it?.importedAt }.distinctUntilChanged(),
    ) { stopId, nearbyIds, revision -> revision to (listOfNotNull(stopId) + nearbyIds).distinct() }
        .mapLatest { (_, ids) -> ids.toSet() to database.board(ids) }
        .flowOn(Dispatchers.IO)

    val boards = combine(board, selection, nearby, time, tripUpdates) { (ids, schedule), picked, nearest, now, live ->
        val stopId = picked.stopId
        val date = picked.date
        val hasBoard = schedule != null && stopId in ids
        val departures = if (hasBoard && stopId != null) {
            if (date == null) schedule.upcoming(stopId, now, live) else schedule.timetable(stopId, date)
        } else emptyList()
        val previews = if (schedule == null) emptyMap() else nearest
            .filter { it.stop.id in ids }
            .associate { item -> item.stop.id to schedule.upcoming(item.stop.id, now, live, 1).firstOrNull() }
        Boards(picked, hasBoard, departures, previews)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, subscribed, Boards())

    val buses = combine(
        vehiclePositions,
        dao.observeMapTrips().map { trips -> trips.associateBy { it.id } },
        dao.observeRoutes().map { routes -> routes.associateBy { it.id } },
        time,
    ) { positions, trips, routes, now ->
        positions?.at(now).orEmpty().map { bus ->
            val trip = trips[bus.tripId]
            MapBus(bus.id, bus.point, trip?.routeNumber ?: routes[bus.routeId]?.number,
                trip?.headsign?.takeIf(String::isNotBlank))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, subscribed, emptyList())

    suspend fun whileVisible() = coroutineScope {
        launch {
            while (isActive) {
                refreshSchedule(force = false)
                delay(15 * 60_000L)
            }
        }
        launch {
            while (isActive) {
                poll("trip updates") {
                    val latest = feed.tripUpdates()
                    if (tripUpdates.value?.timestamp?.isAfter(latest.timestamp) != true) tripUpdates.value = latest
                }
                delay(30_000)
            }
        }
        launch {
            while (isActive) {
                poll("vehicle positions") {
                    val latest = feed.vehiclePositions()
                    if (vehiclePositions.value?.timestamp?.isAfter(latest.timestamp) != true) vehiclePositions.value = latest
                }
                delay(30_000)
            }
        }
        launch {
            while (isActive) {
                _time.value = clock.instant()
                delay(5_000)
            }
        }
    }

    private suspend fun poll(endpoint: String, request: suspend () -> Unit) {
        try {
            request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.d("OpenHSR", "Couldn't refresh $endpoint", error)
        }
    }

    fun retrySchedule() {
        viewModelScope.launch { refreshSchedule(force = true) }
    }

    private suspend fun refreshSchedule(force: Boolean) = refreshLock.withLock {
        _refreshStatus.value = RefreshStatus.LOADING
        try {
            feed.refreshSchedule(force)
            _refreshStatus.value = RefreshStatus.IDLE
        } catch (cancelled: CancellationException) {
            _refreshStatus.value = RefreshStatus.IDLE
            throw cancelled
        } catch (error: Exception) {
            _refreshStatus.value = RefreshStatus.FAILED
            Log.w("OpenHSR", "Couldn't load the HSR schedule", error)
        }
    }

    fun selectStop(stop: Stop) {
        setSelection(StopSelection(stop.id))
        focus(Coordinates(stop.latitude, stop.longitude))
    }

    fun showNearby() = setSelection(StopSelection())

    fun selectDate(date: LocalDate?) = setSelection(selection.value.copy(date = date))

    private fun setSelection(value: StopSelection) {
        _selection.value = value
        savedState["stopId"] = value.stopId
        savedState["date"] = value.date?.toString()
    }

    fun locationDenied() {
        locationJob?.cancel()
        showNearby()
        _location.value = LocationStatus.Denied
    }

    fun locate() {
        locationJob?.cancel()
        showNearby()
        _location.value = LocationStatus.Finding
        locationJob = viewModelScope.launch {
            val point = currentLocation(getApplication())
            _location.value = if (point == null) LocationStatus.Unavailable else LocationStatus.Located(point)
            point?.let(::focus)
        }
    }

    private fun focus(point: Coordinates) {
        _mapFocus.value = MapFocus(point, (_mapFocus.value?.sequence ?: 0) + 1)
    }
}
