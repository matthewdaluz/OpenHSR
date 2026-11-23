package matthewdaluz.openhsr.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "stops")
data class Stop(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val locationType: Int,
) {
    val number: String get() = code.ifBlank { id }
    val isBoardingStop: Boolean get() = locationType == 0
}

@Entity(tableName = "routes")
data class Route(
    @PrimaryKey val id: String,
    val number: String,
    val name: String,
)

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey val id: String,
    val routeId: String,
    val serviceId: String,
    val headsign: String,
)

@Entity(tableName = "stop_times", primaryKeys = ["tripId", "sequence"], indices = [Index("stopId")])
data class StopTime(
    val tripId: String,
    val sequence: Int,
    val stopId: String,
    val arrivalSeconds: Int?,
    val departureSeconds: Int?,
    val headsign: String,
    val pickupType: Int,
)

@Entity(tableName = "service_days")
data class ServiceDays(
    @PrimaryKey val serviceId: String,
    val firstDay: Long,
    val lastDay: Long,
    val dayMask: Int,
)

@Entity(tableName = "service_exceptions", primaryKeys = ["serviceId", "day"])
data class ServiceException(val serviceId: String, val day: Long, val added: Boolean)

@Entity(tableName = "schedule_info")
data class ScheduleInfo(
    @PrimaryKey val id: Int = 1,
    val firstDay: Long,
    val lastDay: Long,
    val maxStopSeconds: Int,
    val importedAt: Long,
    val checkedAt: Long,
    val etag: String?,
    val lastModified: String?,
)

data class BoardRow(
    val tripId: String,
    val sequence: Int,
    val stopId: String,
    val departureSeconds: Int,
    val routeNumber: String,
    val headsign: String,
    val serviceId: String,
)

data class TimingPoint(
    val tripId: String,
    val sequence: Int,
    val stopId: String,
    val arrivalSeconds: Int?,
    val departureSeconds: Int?,
)

data class MapTrip(val id: String, val routeNumber: String, val headsign: String)

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule_info WHERE id = 1")
    fun observeInfo(): Flow<ScheduleInfo?>

    @Query("SELECT * FROM schedule_info WHERE id = 1")
    suspend fun info(): ScheduleInfo?

    @Query("SELECT * FROM stops WHERE locationType = 0 ORDER BY name, id")
    fun observeStops(): Flow<List<Stop>>

    @Query("SELECT * FROM service_days")
    suspend fun serviceDays(): List<ServiceDays>

    @Query("SELECT * FROM service_exceptions")
    suspend fun serviceExceptions(): List<ServiceException>

    @Query(
        """
        SELECT s.tripId, s.sequence, s.stopId, COALESCE(s.departureSeconds, s.arrivalSeconds) AS departureSeconds,
               r.number AS routeNumber, COALESCE(NULLIF(s.headsign, ''), NULLIF(t.headsign, ''), r.name) AS headsign,
               t.serviceId
        FROM stop_times s JOIN trips t ON s.tripId = t.id JOIN routes r ON t.routeId = r.id
        WHERE s.stopId IN (:stopIds) AND s.pickupType != 1
            AND (s.departureSeconds IS NOT NULL OR s.arrivalSeconds IS NOT NULL)
        """,
    )
    suspend fun boardRows(stopIds: List<String>): List<BoardRow>

    @Query(
        """
        SELECT tripId, sequence, stopId, arrivalSeconds, departureSeconds FROM stop_times
        WHERE tripId IN (
            SELECT DISTINCT tripId FROM stop_times WHERE stopId IN (:stopIds) AND pickupType != 1
        )
        ORDER BY tripId, sequence
        """,
    )
    suspend fun timingPoints(stopIds: List<String>): List<TimingPoint>

    @Query("SELECT t.id, r.number AS routeNumber, t.headsign FROM trips t JOIN routes r ON t.routeId = r.id")
    fun observeMapTrips(): Flow<List<MapTrip>>

    @Query("SELECT * FROM routes")
    fun observeRoutes(): Flow<List<Route>>

    @Insert
    suspend fun insertStops(stops: List<Stop>)

    @Insert
    suspend fun insertRoutes(routes: List<Route>)

    @Insert
    suspend fun insertTrips(trips: List<Trip>)

    @Insert
    suspend fun insertStopTimes(times: List<StopTime>)

    @Insert
    suspend fun insertServiceDays(days: List<ServiceDays>)

    @Insert
    suspend fun insertServiceExceptions(exceptions: List<ServiceException>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveInfo(info: ScheduleInfo)

    @Query("DELETE FROM stops")
    suspend fun clearStops()

    @Query("DELETE FROM routes")
    suspend fun clearRoutes()

    @Query("DELETE FROM trips")
    suspend fun clearTrips()

    @Query("DELETE FROM stop_times")
    suspend fun clearStopTimes()

    @Query("DELETE FROM service_days")
    suspend fun clearServiceDays()

    @Query("DELETE FROM service_exceptions")
    suspend fun clearServiceExceptions()

    @Query("UPDATE schedule_info SET checkedAt = :time WHERE id = 1")
    suspend fun markChecked(time: Long)
}

@Database(
    entities = [Stop::class, Route::class, Trip::class, StopTime::class, ServiceDays::class,
        ServiceException::class, ScheduleInfo::class],
    version = 1,
    exportSchema = false,
)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun schedule(): ScheduleDao

    suspend fun board(stopIds: List<String>): BoardSchedule? {
        if (stopIds.isEmpty()) return null
        return withTransaction {
            val dao = schedule()
            val info = dao.info() ?: return@withTransaction null
            BoardSchedule(
                info,
                dao.boardRows(stopIds).groupBy { it.stopId },
                dao.timingPoints(stopIds).groupBy { it.tripId },
                TransitCalendar(dao.serviceDays(), dao.serviceExceptions()),
            )
        }
    }
}
