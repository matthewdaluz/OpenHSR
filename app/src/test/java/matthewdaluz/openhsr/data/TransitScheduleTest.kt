package matthewdaluz.openhsr.data

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitScheduleTest {
    private val monday = LocalDate.of(2026, 11, 23)
    private val sunday = monday.minusDays(1)

    @Test
    fun calendarExceptionsOverrideTheWeeklySchedule() {
        val week = ServiceDays("weekday", sunday.toEpochDay(), monday.plusDays(5).toEpochDay(), 0b0011111)
        val calendar = TransitCalendar(
            listOf(week),
            listOf(
                ServiceException("weekday", monday.toEpochDay(), false),
                ServiceException("holiday", sunday.toEpochDay(), true),
            ),
        )

        assertFalse(calendar.runsOn("weekday", monday))
        assertFalse(calendar.runsOn("weekday", sunday))
        assertTrue(calendar.runsOn("weekday", monday.plusDays(1)))
        assertTrue(calendar.runsOn("holiday", sunday))
        assertFalse(calendar.runsOn("holiday", monday))
    }

    @Test
    fun civilTimetableIncludesThePreviousServiceDayAndUsesTheHamiltonTimeZone() {
        val board = board(times = listOf(25 * 3600 + 12 * 60, 10 * 3600))
        val departures = board.timetable("stop", monday)

        assertEquals(listOf(sunday, monday), departures.map { it.serviceDate })
        assertEquals(
            listOf(Instant.parse("2026-11-23T06:12:00Z"), Instant.parse("2026-11-23T15:00:00Z")),
            departures.map { it.time },
        )
        assertEquals(25 * 3600 + 12 * 60, gtfsSeconds(" 25:12:00 "))
    }

    @Test
    fun serviceClockUsesElapsedTimeAcrossBothDaylightSavingChanges() {
        val spring = LocalDate.of(2026, 3, 8)
        val fall = LocalDate.of(2026, 11, 1)
        assertEquals(Instant.parse("2026-03-08T06:00:00Z"), serviceInstant(spring, 2 * 3600))
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), serviceInstant(spring, 3 * 3600))
        assertEquals(Instant.parse("2026-11-01T05:00:00Z"), serviceInstant(fall, 0))
        assertEquals(Instant.parse("2026-11-01T06:00:00Z"), serviceInstant(fall, 3600))
    }

    @Test
    fun liveUpdateCanBringAnAlreadyScheduledDepartureBackIntoTheBoard() {
        val scheduled = serviceInstant(monday, 8 * 3600)
        val now = scheduled.plusSeconds(120)
        val prediction = scheduled.plusSeconds(240)
        val board = board(times = listOf(8 * 3600, 9 * 3600))
        val update = TripPrediction(
            "trip", monday, now, false, null,
            listOf(stopUpdate(1, event = PredictionEvent(prediction, 99))),
        )
        val departures = board.upcoming("stop", now, TripSnapshot(now, mapOf("trip" to listOf(update))))

        assertEquals(scheduled, departures.first().scheduledAt)
        assertEquals(prediction, departures.first().predictedAt)
        assertEquals(serviceInstant(monday, 9 * 3600).plusSeconds(240), departures[1].predictedAt)
        assertTrue(board.timetable("stop", monday).all { it.predictedAt == null })
    }

    @Test
    fun repeatedStopsUseSequenceAndAmbiguousStopOnlyUpdatesAreIgnored() {
        val board = board(times = listOf(8 * 3600, 9 * 3600))
        val now = serviceInstant(monday, 7 * 3600 + 45 * 60)
        val update = TripPrediction(
            "trip", monday, now, false, null,
            listOf(
                stopUpdate(null, event = PredictionEvent(null, 300)),
                stopUpdate(2, event = PredictionEvent(null, -60)),
            ),
        )
        val departures = board.upcoming("stop", now, TripSnapshot(now, mapOf("trip" to listOf(update))))

        assertEquals(null, departures[0].predictedAt)
        assertEquals(serviceInstant(monday, 9 * 3600 - 60), departures[1].predictedAt)
    }

    @Test
    fun skippedStopCarriesTheDelayAndNoDataStopsPropagation() {
        val update = TripPrediction(
            "trip", monday, serviceInstant(monday, 7 * 3600), false, 90,
            listOf(
                stopUpdate(2, StopRelationship.SKIPPED),
                stopUpdate(4, StopRelationship.NO_DATA),
                stopUpdate(6, event = PredictionEvent(null, -25)),
            ),
        )
        val points = (1..7).map { TimingPoint("trip", it, "stop", 28_800 + it * 60, 28_820 + it * 60) }
        val resolved = resolveTrip(listOf(update), monday, listOf(monday), points)

        assertEquals(setOf(2), resolved.skipped)
        assertEquals(setOf(1, 3, 6, 7), resolved.times.keys)
        assertEquals(serviceInstant(monday, 28_820 + 3 * 60 + 90), resolved.times[3])
        assertEquals(serviceInstant(monday, 28_820 + 7 * 60 - 25), resolved.times[7])
    }

    @Test
    fun freshCancellationAppliesOnlyToItsServiceDateAndOldUpdatesFallBackToSchedule() {
        val now = serviceInstant(monday, 7 * 3600)
        val board = board(times = listOf(8 * 3600))
        val cancelled = TripPrediction("trip", monday, now, true, null, emptyList())
        assertTrue(board.upcoming("stop", now, TripSnapshot(now, mapOf("trip" to listOf(cancelled)))).isEmpty())
        assertEquals(
            1,
            board.upcoming("stop", now, TripSnapshot(now, mapOf("trip" to listOf(cancelled.copy(startDate = sunday))))).size,
        )
        assertEquals(
            1,
            board.upcoming("stop", now, TripSnapshot(now.minusSeconds(91), mapOf("trip" to listOf(cancelled)))).size,
        )
        assertEquals(
            1,
            board.upcoming("stop", now, TripSnapshot(now, mapOf("trip" to listOf(cancelled.copy(timestamp = now.minusSeconds(91)))))).size,
        )
    }

    @Test
    fun anAbsolutePredictionCanIdentifyAnUndatedTripOccurrence() {
        val time = serviceInstant(monday, 8 * 3600)
        val now = time.minusSeconds(600)
        val board = board(times = listOf(8 * 3600))
        val update = TripPrediction(
            "trip", null, now, false, null,
            listOf(stopUpdate(1, event = PredictionEvent(time.plusSeconds(60), null))),
        )
        assertEquals(
            time.plusSeconds(60),
            board.upcoming("stop", now, TripSnapshot(now, mapOf("trip" to listOf(update)))).single().predictedAt,
        )
    }

    @Test
    fun vehicleFreshnessChecksBothTheFeedAndTheIndividualPosition() {
        val now = serviceInstant(monday, 7 * 3600)
        val bus = VehiclePosition("bus", "trip", "route", Coordinates(43.25, -79.87), now)
        val oldBus = bus.copy(id = "old", timestamp = now.minusSeconds(91))
        assertEquals(listOf(bus), VehicleSnapshot(now, listOf(bus, oldBus)).at(now))
        assertTrue(VehicleSnapshot(now.minusSeconds(91), listOf(bus)).at(now).isEmpty())
    }

    @Test
    fun nearbyStopsAreBoardingStopsWithinTheRadiusAndOrderedByDistance() {
        val origin = Coordinates(43.25, -79.85)
        val locations = (0..6).map { index ->
            Stop("$index", "", "Stop $index", origin.latitude + index * 0.001, origin.longitude, 0)
        }
        val station = locations[0].copy(id = "station", locationType = 1)
        val outlying = locations[0].copy(id = "outlying", latitude = 43.26)
        val nearby = nearbyStops(origin, listOf(station, outlying) + locations.reversed())
        assertEquals(listOf("0", "1", "2", "3", "4"), nearby.map { it.stop.id })
        assertEquals(0, nearby.first().distanceMetres)
        assertEquals(listOf("0"), nearbyStops(origin, listOf(station, outlying, locations[0])).map { it.stop.id })
        assertEquals(listOf("2"), searchStops(locations, "stop 2").map { it.id })
    }

    private fun stopUpdate(
        sequence: Int?,
        relationship: StopRelationship = StopRelationship.SCHEDULED,
        event: PredictionEvent? = null,
    ) = StopPrediction(sequence, "stop", relationship, null, event)

    private fun board(times: List<Int>): BoardSchedule {
        val first = sunday.minusDays(2).toEpochDay()
        val last = monday.plusDays(4).toEpochDay()
        return BoardSchedule(
            ScheduleInfo(
                firstDay = first, lastDay = last, maxStopSeconds = times.max(),
                importedAt = 0, checkedAt = 0, etag = null, lastModified = null,
            ),
            mapOf("stop" to times.mapIndexed { index, time ->
                BoardRow("trip", index + 1, "stop", time, "01", "CENTRE", "everyday")
            }),
            mapOf("trip" to times.mapIndexed { index, time -> TimingPoint("trip", index + 1, "stop", time, time) }),
            TransitCalendar(listOf(ServiceDays("everyday", first, last, 0b1111111)), emptyList()),
        )
    }
}
