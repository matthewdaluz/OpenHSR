package io.github.matthewdaluz.openhsr.data

import com.google.transit.realtime.GtfsRealtime.FeedHeader.Incrementality
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripUpdate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private const val LIVE_SECONDS = 90L

private fun Instant.isFresh(now: Instant): Boolean =
    this >= now.minusSeconds(LIVE_SECONDS) && this <= now.plusSeconds(30L)

data class TripSnapshot(val timestamp: Instant, val updates: Map<String, List<TripPrediction>>) {
    fun isFresh(now: Instant): Boolean = timestamp.isFresh(now)

    fun at(now: Instant): Map<String, List<TripPrediction>> =
        if (isFresh(now)) updates.mapValues { (_, trips) -> trips.filter { it.timestamp.isFresh(now) } }
        else emptyMap()
}

data class TripPrediction(
    val tripId: String,
    val startDate: LocalDate?,
    val timestamp: Instant,
    val cancelled: Boolean,
    val delaySeconds: Int?,
    val stops: List<StopPrediction>,
)

data class StopPrediction(
    val sequence: Int?,
    val stopId: String?,
    val relationship: StopRelationship,
    val arrival: PredictionEvent?,
    val departure: PredictionEvent?,
)

enum class StopRelationship { SCHEDULED, SKIPPED, NO_DATA }

data class PredictionEvent(val time: Instant?, val delaySeconds: Int?) {
    fun delayAt(date: LocalDate, scheduledSeconds: Int?): Long? {
        if (time != null && scheduledSeconds != null) {
            return Duration.between(serviceInstant(date, scheduledSeconds), time).seconds
        }
        return delaySeconds?.toLong()
    }
}

data class VehicleSnapshot(val timestamp: Instant, val vehicles: List<VehiclePosition>) {
    fun at(now: Instant): List<VehiclePosition> =
        if (timestamp.isFresh(now)) vehicles.filter { it.timestamp.isFresh(now) } else emptyList()
}

data class VehiclePosition(
    val id: String,
    val tripId: String,
    val routeId: String,
    val point: Coordinates,
    val timestamp: Instant,
)

internal fun validateRealtime(message: FeedMessage) {
    require(message.header.hasTimestamp() && message.header.timestamp > 0) { "Missing GTFS Realtime timestamp" }
    require(message.header.incrementality == Incrementality.FULL_DATASET) { "Expected a complete GTFS Realtime feed" }
}

internal fun decodeTrips(message: FeedMessage): TripSnapshot {
    val stamp = Instant.ofEpochSecond(message.header.timestamp)
    val updates = message.entityList.mapNotNull { entity ->
        if (entity.isDeleted || !entity.hasTripUpdate()) return@mapNotNull null
        val update = entity.tripUpdate
        val trip = update.trip
        if (trip.tripId.isBlank()) return@mapNotNull null
        val cancelled = when (trip.scheduleRelationship) {
            TripDescriptor.ScheduleRelationship.CANCELED, TripDescriptor.ScheduleRelationship.DELETED -> true
            TripDescriptor.ScheduleRelationship.SCHEDULED -> false
            else -> return@mapNotNull null
        }
        val date = if (trip.startDate.isBlank()) null else {
            runCatching { gtfsDate(trip.startDate) }.getOrNull() ?: return@mapNotNull null
        }
        TripPrediction(
            trip.tripId, date,
            if (update.hasTimestamp()) Instant.ofEpochSecond(update.timestamp) else stamp,
            cancelled, update.delay.takeIf { update.hasDelay() },
            update.stopTimeUpdateList.mapNotNull(::decodeStop),
        )
    }
    return TripSnapshot(stamp, updates.groupBy { it.tripId })
}

private fun decodeStop(update: TripUpdate.StopTimeUpdate): StopPrediction? {
    val relationship = when (update.scheduleRelationship) {
        TripUpdate.StopTimeUpdate.ScheduleRelationship.SCHEDULED -> StopRelationship.SCHEDULED
        TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED -> StopRelationship.SKIPPED
        TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA -> StopRelationship.NO_DATA
        else -> return null
    }
    return StopPrediction(
        update.stopSequence.takeIf { update.hasStopSequence() },
        update.stopId.takeIf(String::isNotBlank), relationship,
        update.arrival.event().takeIf { update.hasArrival() },
        update.departure.event().takeIf { update.hasDeparture() },
    )
}

private fun TripUpdate.StopTimeEvent.event(): PredictionEvent =
    PredictionEvent(if (hasTime()) Instant.ofEpochSecond(time) else null, delay.takeIf { hasDelay() })

internal fun decodeVehicles(message: FeedMessage): VehicleSnapshot {
    val stamp = Instant.ofEpochSecond(message.header.timestamp)
    val vehicles = message.entityList.mapNotNull { entity ->
        if (entity.isDeleted || !entity.hasVehicle() || !entity.vehicle.hasPosition()) return@mapNotNull null
        val vehicle = entity.vehicle
        val trip = vehicle.trip
        val point = Coordinates(vehicle.position.latitude.toDouble(), vehicle.position.longitude.toDouble())
        if (point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return@mapNotNull null
        val recorded = if (vehicle.hasTimestamp()) Instant.ofEpochSecond(vehicle.timestamp) else stamp
        VehiclePosition(vehicle.vehicle.id.ifBlank { entity.id }, trip.tripId, trip.routeId, point, recorded)
    }
    return VehicleSnapshot(stamp, vehicles.groupBy { it.id }.values.mapNotNull { group -> group.maxByOrNull { it.timestamp } })
}

internal class ResolvedTrip(
    val cancelled: Boolean = false,
    val times: Map<Int, Instant> = emptyMap(),
    val skipped: Set<Int> = emptySet(),
)

internal fun resolveTrip(
    updates: List<TripPrediction>,
    date: LocalDate,
    activeDates: List<LocalDate>,
    timing: List<TimingPoint>,
): ResolvedTrip {
    val update = updates.asSequence()
        .filter { (it.startDate ?: inferServiceDate(it, activeDates, timing)) == date }
        .maxWithOrNull(compareBy<TripPrediction>({ it.startDate != null }, { it.timestamp }))
        ?: return ResolvedTrip()
    if (update.cancelled) return ResolvedTrip(cancelled = true)

    val events = update.stops.mapNotNull { event -> event.point(timing)?.sequence?.let { it to event } }.toMap()
    val times = mutableMapOf<Int, Instant>()
    val skipped = mutableSetOf<Int>()
    var carriedDelay = update.delaySeconds?.toLong()
    for (point in timing) {
        val event = events[point.sequence]
        when (event?.relationship) {
            StopRelationship.SKIPPED -> {
                skipped.add(point.sequence)
                continue
            }
            StopRelationship.NO_DATA -> {
                carriedDelay = null
                continue
            }
            StopRelationship.SCHEDULED, null -> {
                carriedDelay = event?.departure?.delayAt(date, point.departureSeconds ?: point.arrivalSeconds)
                    ?: event?.arrival?.delayAt(date, point.arrivalSeconds ?: point.departureSeconds)
                    ?: carriedDelay
            }
        }
        val scheduled = point.departureSeconds ?: point.arrivalSeconds
        val time = event?.departure?.time
            ?: carriedDelay?.let { delay -> scheduled?.let { serviceInstant(date, it).plusSeconds(delay) } }
        if (time != null) times[point.sequence] = time
    }
    return ResolvedTrip(times = times, skipped = skipped)
}

private fun StopPrediction.point(timing: List<TimingPoint>): TimingPoint? =
    if (sequence != null) timing.firstOrNull { it.sequence == sequence && (stopId == null || it.stopId == stopId) }
    else timing.singleOrNull { it.stopId == stopId }

private fun inferServiceDate(
    update: TripPrediction,
    dates: List<LocalDate>,
    timing: List<TimingPoint>,
): LocalDate? {
    if (dates.size <= 1) return dates.singleOrNull()
    val anchor = update.stops.firstNotNullOfOrNull { stop ->
        val point = stop.point(timing) ?: return@firstNotNullOfOrNull null
        val time = stop.departure?.time ?: stop.arrival?.time ?: return@firstNotNullOfOrNull null
        val seconds = if (stop.departure?.time != null) point.departureSeconds ?: point.arrivalSeconds
            else point.arrivalSeconds ?: point.departureSeconds
        seconds?.let { time to it }
    } ?: return null
    val (time, seconds) = anchor
    return dates.singleOrNull { date -> Duration.between(serviceInstant(date, seconds), time).abs() < Duration.ofHours(12) }
}
