package nl.maalstroom.marine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryPoint(
    val timestamp: Long,
    val depth: Double?,
    val windDirection: Double?,
    val windSpeed: Double?
)

class MarineDatabase(context: Context) :
    SQLiteOpenHelper(context, "maalstroom_marine.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history (
                timestamp INTEGER PRIMARY KEY,
                depth REAL,
                wind_direction REAL,
                wind_speed REAL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX idx_history_timestamp ON history(timestamp)"
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Eerste databaseversie: nog geen migraties nodig.
    }

    fun insertPoint(point: HistoryPoint) {
        writableDatabase.execSQL(
            """
            INSERT OR REPLACE INTO history
                (timestamp, depth, wind_direction, wind_speed)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                point.timestamp,
                point.depth,
                point.windDirection,
                point.windSpeed
            )
        )
    }

    fun readSince(sinceMillis: Long): List<HistoryPoint> {
        val result = mutableListOf<HistoryPoint>()

        readableDatabase.rawQuery(
            """
            SELECT timestamp, depth, wind_direction, wind_speed
            FROM history
            WHERE timestamp >= ?
            ORDER BY timestamp ASC
            """.trimIndent(),
            arrayOf(sinceMillis.toString())
        ).use { cursor ->
            val timestampColumn = cursor.getColumnIndexOrThrow("timestamp")
            val depthColumn = cursor.getColumnIndexOrThrow("depth")
            val directionColumn =
                cursor.getColumnIndexOrThrow("wind_direction")
            val speedColumn =
                cursor.getColumnIndexOrThrow("wind_speed")

            while (cursor.moveToNext()) {
                result += HistoryPoint(
                    timestamp = cursor.getLong(timestampColumn),
                    depth =
                        if (cursor.isNull(depthColumn)) null
                        else cursor.getDouble(depthColumn),
                    windDirection =
                        if (cursor.isNull(directionColumn)) null
                        else cursor.getDouble(directionColumn),
                    windSpeed =
                        if (cursor.isNull(speedColumn)) null
                        else cursor.getDouble(speedColumn)
                )
            }
        }

        return result
    }

    fun deleteOlderThan(cutoffMillis: Long) {
        writableDatabase.delete(
            "history",
            "timestamp < ?",
            arrayOf(cutoffMillis.toString())
        )
    }
}
