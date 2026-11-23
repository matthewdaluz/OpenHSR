package io.github.matthewdaluz.openhsr.data

import androidx.room.withTransaction
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord

private const val CITY = "https://opendata.hamilton.ca"
private const val USER_AGENT = "OpenHSR/0.1.0 (+https://github.com/matthewdaluz/OpenHSR)"
private const val MAX_SCHEDULE_BYTES = 30L * 1024 * 1024
private const val DAY_MILLIS = 86_400_000L

class HamiltonFeed(
    private val database: ScheduleDatabase,
    private val cacheDirectory: File,
    private val clock: Clock,
) {
    private val refreshLock = Mutex()

    suspend fun refreshSchedule(force: Boolean = false) = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val old = database.schedule().info()
            if (!force && old != null && clock.millis() - old.checkedAt in 0 until DAY_MILLIS) {
                return@withContext
            }
            request("$CITY/GTFS-Static/google_transit.zip", "application/zip").useConnection { connection ->
                old?.etag?.let { connection.setRequestProperty("If-None-Match", it) }
                old?.lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
                if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && old != null) {
                    database.schedule().markChecked(clock.millis())
                    return@useConnection
                }
                connection.requireOk()
                val file = File.createTempFile("hsr-schedule-", ".zip", cacheDirectory)
                try {
                    connection.inputStream.use { input ->
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                total += count
                                if (total > MAX_SCHEDULE_BYTES) throw IOException("GTFS archive is too large")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    ZipFile(file).use { zip ->
                        ScheduleImporter(database).import(
                            zip,
                            connection.getHeaderField("ETag"),
                            connection.getHeaderField("Last-Modified"),
                            clock.millis(),
                        )
                    }
                } finally {
                    file.delete()
                }
            }
        }
    }

    suspend fun tripUpdates(): TripSnapshot =
        withContext(Dispatchers.IO) { decodeTrips(realtime("GTFS_TripUpdates.pb")) }

    suspend fun vehiclePositions(): VehicleSnapshot =
        withContext(Dispatchers.IO) { decodeVehicles(realtime("GTFS_VehiclePositions.pb")) }

    private fun realtime(file: String): FeedMessage =
        request("$CITY/GTFS-RT/$file", "application/octet-stream").useConnection { connection ->
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.requireOk()
            connection.inputStream.use { FeedMessage.parseFrom(it).also(::validateRealtime) }
        }

    private fun request(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
        }
}

private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
    try {
        block(this)
    } finally {
        disconnect()
    }

private fun HttpURLConnection.requireOk() {
    if (responseCode != HttpURLConnection.HTTP_OK) throw IOException("Hamilton GTFS returned HTTP $responseCode")
}

internal class ScheduleImporter(private val database: ScheduleDatabase) {
    suspend fun import(zip: ZipFile, etag: String?, modified: String?, time: Long) {
        val agency = zip.rows("agency.txt") { it.required("agency_timezone") }
        require(agency.isNotEmpty() && agency.all { it == HamiltonZone.id }) { "Unexpected GTFS timezone" }
        val stops = zip.rows("stops.txt") {
            Stop(
                it.required("stop_id"),
                it.value("stop_code"),
                it.required("stop_name"),
                it.required("stop_lat").toDouble().also { latitude -> require(latitude in -90.0..90.0) },
                it.required("stop_lon").toDouble().also { longitude -> require(longitude in -180.0..180.0) },
                it.value("location_type").ifBlank { "0" }.toInt(),
            )
        }
        val routes = zip.rows("routes.txt") {
            val id = it.required("route_id")
            val name = it.value("route_long_name")
            Route(id, it.value("route_short_name").ifBlank { name.ifBlank { id } }, name)
        }
        val trips = zip.rows("trips.txt") {
            Trip(it.required("trip_id"), it.required("route_id"), it.required("service_id"), it.value("trip_headsign"))
        }
        val calendar = zip.rows("calendar.txt", optional = true) {
            val first = gtfsDate(it.required("start_date")).toEpochDay()
            val last = gtfsDate(it.required("end_date")).toEpochDay()
            require(first <= last) { "Invalid service date range" }
            ServiceDays(it.required("service_id"), first, last, it.weekMask())
        }
        val exceptions = zip.rows("calendar_dates.txt", optional = true) {
            val type = it.required("exception_type").toInt()
            require(type == 1 || type == 2) { "Invalid calendar exception" }
            ServiceException(it.required("service_id"), gtfsDate(it.required("date")).toEpochDay(), type == 1)
        }
        val published = zip.rows("feed_info.txt", optional = true) { it.value("feed_start_date") to it.value("feed_end_date") }
            .firstOrNull()
        val activeExceptions = exceptions.filter { it.added }
        val minDays = calendar.map { it.firstDay } + activeExceptions.map { it.day }
        val maxDays = calendar.map { it.lastDay } + activeExceptions.map { it.day }
        val first = published?.first?.takeIf(String::isNotEmpty)?.let { gtfsDate(it).toEpochDay() } ?: minDays.min()
        val last = published?.second?.takeIf(String::isNotEmpty)?.let { gtfsDate(it).toEpochDay() } ?: maxDays.max()
        val routeIds = routes.mapTo(HashSet()) { it.id }
        val tripIds = trips.mapTo(HashSet()) { it.id }
        val stopIds = stops.mapTo(HashSet()) { it.id }
        val serviceIds = (calendar.map { it.serviceId } + activeExceptions.map { it.serviceId }).toSet()
        require(first <= last && stops.any(Stop::isBoardingStop) && trips.isNotEmpty() &&
            trips.all { it.routeId in routeIds && it.serviceId in serviceIds }
        ) { "Incomplete GTFS schedule" }

        database.withTransaction {
            val dao = database.schedule()
            dao.clearStopTimes()
            dao.clearTrips()
            dao.clearRoutes()
            dao.clearStops()
            dao.clearServiceDays()
            dao.clearServiceExceptions()
            dao.insertStops(stops)
            dao.insertRoutes(routes)
            dao.insertTrips(trips)
            dao.insertServiceDays(calendar)
            dao.insertServiceExceptions(exceptions)
            var maximum = 0
            var timedBoardingStops = 0
            zip.parse("stop_times.txt").use { parser ->
                val batch = ArrayList<StopTime>(2_000)
                for (row in parser) {
                    val tripId = row.required("trip_id")
                    val stopId = row.required("stop_id")
                    require(tripId in tripIds && stopId in stopIds) { "Unknown trip or stop in stop_times.txt" }
                    val arrival = row.value("arrival_time").takeIf(String::isNotEmpty)?.let(::gtfsSeconds)
                    val departure = row.value("departure_time").takeIf(String::isNotEmpty)?.let(::gtfsSeconds)
                    val sequence = row.required("stop_sequence").toInt().also { require(it >= 0) }
                    val pickup = row.value("pickup_type").ifBlank { "0" }.toInt()
                    maximum = maxOf(maximum, arrival ?: 0, departure ?: 0)
                    if (pickup != 1 && (arrival != null || departure != null)) timedBoardingStops++
                    batch.add(StopTime(tripId, sequence, stopId, arrival, departure, row.value("stop_headsign"), pickup))
                    if (batch.size == 2_000) {
                        currentCoroutineContext().ensureActive()
                        dao.insertStopTimes(batch)
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) dao.insertStopTimes(batch)
            }
            require(timedBoardingStops > 0) { "No timed boarding stops in GTFS schedule" }
            dao.saveInfo(ScheduleInfo(firstDay = first, lastDay = last, maxStopSeconds = maximum,
                importedAt = time, checkedAt = time, etag = etag, lastModified = modified))
        }
    }
}

private val csv = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).build()

private fun ZipFile.parse(name: String): CSVParser {
    val entry = getEntry(name) ?: throw IOException("GTFS archive is missing $name")
    val reader = getInputStream(entry).bufferedReader(Charsets.UTF_8)
    try {
        reader.mark(1)
        if (reader.read() != 0xFEFF) reader.reset()
        return csv.parse(reader)
    } catch (error: Exception) {
        reader.close()
        throw error
    }
}

private inline fun <T> ZipFile.rows(
    name: String,
    optional: Boolean = false,
    transform: (CSVRecord) -> T,
): List<T> = if (optional && getEntry(name) == null) emptyList() else parse(name).use { it.map(transform) }

private fun CSVRecord.value(column: String): String = if (isSet(column)) get(column).trim() else ""

private fun CSVRecord.required(column: String): String =
    value(column).also { require(it.isNotEmpty()) { "GTFS row $recordNumber is missing $column" } }

private fun CSVRecord.weekMask(): Int =
    listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        .mapIndexed { index, day -> if (required(day) == "1") 1 shl index else 0 }.sum()

internal fun gtfsDate(date: String): LocalDate = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)

internal fun gtfsSeconds(time: String): Int {
    val parts = time.trim().split(':')
    require(parts.size == 3) { "Invalid GTFS time" }
    val (hour, minute, second) = parts.map(String::toInt)
    require(hour in 0..99 && minute in 0..59 && second in 0..59) { "Invalid GTFS time" }
    return hour * 3600 + minute * 60 + second
}
