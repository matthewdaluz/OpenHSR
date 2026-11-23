package io.github.matthewdaluz.openhsr.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

val HamiltonZone: ZoneId = ZoneId.of("America/Toronto")
private const val SECONDS_PER_DAY = 86_400L

data class Coordinates(val latitude: Double, val longitude: Double)

data class NearbyStop(val stop: Stop, val distanceMetres: Int)

fun nearbyStops(origin: Coordinates, stops: List<Stop>): List<NearbyStop> =
    stops.asSequence()
        .filter(Stop::isBoardingStop)
        .map { stop -> stop to distanceMetres(origin, Coordinates(stop.latitude, stop.longitude)) }
        .filter { (_, distance) -> distance <= 800 }
        .sortedWith(compareBy({ it.second }, { it.first.id }))
        .take(5)
        .map { (stop, distance) -> NearbyStop(stop, distance.toInt()) }
        .toList()

private fun distanceMetres(from: Coordinates, to: Coordinates): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val latDifference = lat2 - lat1
    val lonDifference = Math.toRadians(to.longitude - from.longitude)
    val chord = sin(latDifference / 2).let { it * it } +
        cos(lat1) * cos(lat2) * sin(lonDifference / 2).let { it * it }
    return 2 * 6_371_000 * asin(sqrt(chord.coerceIn(0.0, 1.0)))
}

fun searchStops(stops: List<Stop>, query: String): List<Stop> {
    val tokens = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (tokens.isEmpty()) return emptyList()
    return stops.asSequence()
        .filter { stop ->
            tokens.all { token ->
                stop.name.contains(token, ignoreCase = true) || stop.number.contains(token)
            }
        }
        .sortedWith(compareBy({ it.number != query.trim() }, { it.name }, { it.id }))
        .take(30)
        .toList()
}

class TransitCalendar(days: List<ServiceDays>, exceptions: List<ServiceException>) {
    private val schedules = days.associateBy { it.serviceId }
    private val overrides = exceptions.groupBy { it.serviceId }.mapValues { (_, entries) ->
        entries.associate { it.day to it.added }
    }

    fun runsOn(serviceId: String, date: LocalDate): Boolean {
        val day = date.toEpochDay()
        overrides[serviceId]?.get(day)?.let { return it }
        val schedule = schedules[serviceId] ?: return false
        return day in schedule.firstDay..schedule.lastDay &&
            schedule.dayMask and (1 shl (date.dayOfWeek.value - 1)) != 0
    }
}

// GTFS measures time from local noon minus twelve elapsed hours, including on DST changes.
fun serviceInstant(serviceDate: LocalDate, seconds: Int): Instant =
    serviceDate.atTime(LocalTime.NOON).atZone(HamiltonZone).toInstant()
        .plusSeconds(seconds - 12 * 3600L)

val ScheduleInfo.firstDate: LocalDate get() = LocalDate.ofEpochDay(firstDay)
val ScheduleInfo.lastDate: LocalDate get() = LocalDate.ofEpochDay(lastDay)
val ScheduleInfo.lastBoardDate: LocalDate get() = lastDate.plusDays(maxStopSeconds / SECONDS_PER_DAY)

data class Departure(
    val tripId: String,
    val sequence: Int,
    val serviceDate: LocalDate,
    val routeNumber: String,
    val headsign: String,
    val scheduledAt: Instant,
    val predictedAt: Instant?,
) {
    val time: Instant get() = predictedAt ?: scheduledAt
}

class BoardSchedule(
    val info: ScheduleInfo,
    private val rowsByStop: Map<String, List<BoardRow>>,
    private val timingByTrip: Map<String, List<TimingPoint>>,
    private val calendar: TransitCalendar,
) {
    fun upcoming(stopId: String, now: Instant, realtime: TripSnapshot?, limit: Int = 12): List<Departure> =
        departures(stopId, now, now.plusSeconds(SECONDS_PER_DAY), realtime?.at(now)).take(limit)

    fun timetable(stopId: String, date: LocalDate): List<Departure> =
        departures(
            stopId,
            date.atStartOfDay(HamiltonZone).toInstant(),
            date.plusDays(1).atStartOfDay(HamiltonZone).toInstant(),
            emptyMap(),
        )

    private fun departures(
        stopId: String,
        from: Instant,
        until: Instant,
        realtime: Map<String, List<TripPrediction>>?,
    ): List<Departure> {
        val rows = rowsByStop[stopId].orEmpty()
        if (rows.isEmpty()) return emptyList()
        val first = maxOf(info.firstDate, from.atZone(HamiltonZone).toLocalDate()
            .minusDays(info.maxStopSeconds / SECONDS_PER_DAY + 1))
        val last = minOf(info.lastDate, until.atZone(HamiltonZone).toLocalDate().plusDays(1))
        val dates = generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()

        val resolvedUpdates = mutableMapOf<Pair<String, LocalDate>, ResolvedTrip>()
        return rows.flatMap { row ->
            val serviceDates = dates.filter { calendar.runsOn(row.serviceId, it) }
            val updates = realtime?.get(row.tripId).orEmpty()
            serviceDates.mapNotNull { date ->
                val scheduledAt = serviceInstant(date, row.departureSeconds)
                val trip = if (updates.isEmpty()) null else resolvedUpdates.getOrPut(row.tripId to date) {
                    resolveTrip(updates, date, serviceDates, timingByTrip[row.tripId].orEmpty())
                }
                if (trip?.cancelled == true || row.sequence in trip?.skipped.orEmpty()) return@mapNotNull null
                val prediction = trip?.times?.get(row.sequence)
                val time = prediction ?: scheduledAt
                if (time < from || time >= until) return@mapNotNull null
                Departure(row.tripId, row.sequence, date, row.routeNumber, row.headsign, scheduledAt, prediction)
            }
        }.sortedWith(compareBy({ it.time }, { it.routeNumber }, { it.tripId }, { it.sequence }))
    }
}
