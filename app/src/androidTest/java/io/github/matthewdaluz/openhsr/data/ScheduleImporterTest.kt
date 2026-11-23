package io.github.matthewdaluz.openhsr.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleImporterTest {
    @Test
    fun malformedReplacementRollsBackAllChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = archive(context, "Original stop", "stop")
        val broken = archive(context, "Replacement stop", "nonexistent-stop")
        val date = LocalDate.of(2026, 11, 23)
        val db = Room.inMemoryDatabaseBuilder(context, ScheduleDatabase::class.java).build()

        try {
            val importer = ScheduleImporter(db)
            runBlocking {
                ZipFile(original).use { importer.import(it, "original", null, 1) }
                assertEquals("ORIGINAL ROUTE", db.board(listOf("stop"))?.timetable("stop", date)?.single()?.headsign)
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { ZipFile(broken).use { importer.import(it, "broken", null, 2) } }
            }
            runBlocking {
                assertEquals("original", db.schedule().info()?.etag)
                assertEquals("ORIGINAL ROUTE", db.board(listOf("stop"))?.timetable("stop", date)?.single()?.headsign)
            }
        } finally {
            db.close()
            original.delete()
            broken.delete()
        }
    }

    private fun archive(context: Context, name: String, stopId: String): File {
        val contents = mapOf(
            "agency.txt" to "agency_timezone\nAmerica/Toronto\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nstop,$name,43.25,-79.85\n",
            "routes.txt" to "route_id,route_short_name,route_long_name\nroute,1,ORIGINAL ROUTE\n",
            "trips.txt" to "trip_id,route_id,service_id\ntrip,route,holiday\n",
            "calendar_dates.txt" to "service_id,date,exception_type\nholiday,20261123,1\n",
            "stop_times.txt" to "trip_id,stop_sequence,stop_id,arrival_time,departure_time\ntrip,1,$stopId,8:00:00,8:00:00\n",
        )
        return File.createTempFile("schedule-test", ".zip", context.cacheDir).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((entry, csv) in contents) {
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(csv.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }
}
