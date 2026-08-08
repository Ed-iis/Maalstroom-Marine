package nl.maalstroom.marine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.floor

class NmeaBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "nmea_background"
        private const val NOTIFICATION_ID = 1001
        private const val UDP_PORT = 10110
        private const val SAMPLE_INTERVAL_MS = 10_000L
        private const val MAX_PACKET_SIZE = 65_535
        private const val CSV_FOLDER = "Maalstroom Marine"
        private const val KEEP_HISTORY_DAYS = 7L
    }

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var database: MarineDatabase

    private val csvUriCache = mutableMapOf<String, Uri>()

    override fun onCreate() {
        super.onCreate()

        database = MarineDatabase(this)
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Maalstroom Marine")
                .setContentText("NMEA registratie actief")
                .setOngoing(true)
                .setSilent(true)
                .build()
        )

        acquireLocks()
        startUdpReceiver()
        startSampler()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        socket?.close()
        socket = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }

        wifiLock?.let {
            if (it.isHeld) it.release()
        }

        database.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NMEA registratie",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Houdt NMEA-ontvangst en logging actief op de achtergrond."
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val powerManager =
            getSystemService(POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MaalstroomMarine::BackgroundNmea"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager =
            applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "MaalstroomMarine::Wifi"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startUdpReceiver() {
        if (!running.compareAndSet(false, true)) return

        thread(name = "NMEA-Background-UDP", isDaemon = true) {
            while (running.get()) {
                try {
                    val udpSocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress("0.0.0.0", UDP_PORT))
                    }

                    socket = udpSocket

                    val packetBuffer = ByteArray(MAX_PACKET_SIZE)
                    val packet =
                        DatagramPacket(packetBuffer, packetBuffer.size)
                    val lineBuffer = StringBuilder()

                    while (running.get()) {
                        packet.length = packetBuffer.size
                        udpSocket.receive(packet)

                        val text = String(
                            packet.data,
                            packet.offset,
                            packet.length,
                            StandardCharsets.US_ASCII
                        )

                        lineBuffer.append(
                            text.replace('\r', '\n')
                        )

                        val complete =
                            lineBuffer.toString().split('\n')

                        lineBuffer.clear()

                        if (
                            !text.endsWith("\n") &&
                            !text.endsWith("\r")
                        ) {
                            lineBuffer.append(
                                complete.lastOrNull().orEmpty()
                            )
                        }

                        val limit =
                            if (lineBuffer.isNotEmpty()) {
                                complete.size - 1
                            } else {
                                complete.size
                            }

                        for (i in 0 until limit) {
                            val sentence = complete[i].trim()
                            if (sentence.isNotEmpty()) {
                                parseSentence(sentence)
                            }
                        }
                    }
                } catch (_: Exception) {
                    socket?.close()
                    socket = null

                    if (running.get()) {
                        Thread.sleep(2_000)
                    }
                }
            }
        }
    }

    private fun startSampler() {
        thread(name = "NMEA-Background-Sampler", isDaemon = true) {
            var lastCleanupDay = ""

            while (running.get()) {
                val started = System.currentTimeMillis()
                val snapshot = NmeaStateStore.snapshot()

                val point = HistoryPoint(
                    timestamp = started,
                    depth =
                        snapshot.depthRaw?.plus(snapshot.depthOffset),
                    windDirection = snapshot.trueWindDirection,
                    windSpeed = snapshot.trueWindSpeedKnots
                )

                if (
                    point.depth != null ||
                    point.windDirection != null ||
                    point.windSpeed != null
                ) {
                    try {
                        database.insertPoint(point)
                    } catch (_: Exception) {
                    }
                }

                if (
                    point.windDirection != null &&
                    point.windSpeed != null
                ) {
                    try {
                        appendWindCsv(point)
                    } catch (_: Exception) {
                    }
                }

                val today =
                    SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(Date(started))

                if (today != lastCleanupDay) {
                    lastCleanupDay = today
                    val cutoff =
                        started -
                            KEEP_HISTORY_DAYS *
                            24L * 60L * 60L * 1000L

                    try {
                        database.deleteOlderThan(cutoff)
                    } catch (_: Exception) {
                    }
                }

                val elapsed =
                    System.currentTimeMillis() - started
                val sleep =
                    (SAMPLE_INTERVAL_MS - elapsed)
                        .coerceAtLeast(500L)

                Thread.sleep(sleep)
            }
        }
    }

    private fun parseSentence(sentence: String) {
        if (
            !sentence.startsWith("$") ||
            !isChecksumValid(sentence)
        ) {
            return
        }

        val body =
            sentence.substring(1, sentence.indexOf('*'))
        val fields = body.split(',')

        if (fields.isEmpty()) return

        val talkerAndType = fields[0]
        val talker = talkerAndType.take(2)
        val type = talkerAndType.takeLast(3)

        when (type) {
            "RMC" -> parseRmc(fields)
            "DPT" -> parseDpt(fields)
            "HDG" -> if (talker == "II") parseIiHdg(fields)
            "MWV" -> if (talker == "II") parseIiMwv(fields)
            "VHW" -> if (talker == "II") parseIiVhw(fields)
            "RSA" -> parseRsa(fields)
            "XDR" -> parseXdr(fields)
            "RMB" -> parseRmb(fields)
            "APB" -> parseApb(fields)
        }
    }

    private fun parseRmc(fields: List<String>) {
        if (fields.size < 9 || fields[2] != "A") return

        val latRaw = fields[3]
        val latHem = fields[4]
        val lonRaw = fields[5]
        val lonHem = fields[6]

        if (
            coordinateToDecimal(latRaw, latHem) == null ||
            coordinateToDecimal(lonRaw, lonHem) == null
        ) {
            return
        }

        val sog = fields[7].toDoubleOrNull()
        val cog = fields[8].toDoubleOrNull()
        val now = System.currentTimeMillis()

        NmeaStateStore.update { old ->
            old.copy(
                latitudeDisplay =
                    formatCoordinate(latRaw, latHem),
                longitudeDisplay =
                    formatCoordinate(lonRaw, lonHem),
                positionAvailable = true,
                sogKnots = sog,
                cogDegrees = cog,
                lastNmeaMillis = now
            )
        }
    }

    private fun parseDpt(fields: List<String>) {
        val raw =
            fields.getOrNull(1)?.toDoubleOrNull()
                ?: return

        val offset =
            fields.getOrNull(2)?.toDoubleOrNull()
                ?: 0.0

        NmeaStateStore.update { old ->
            old.copy(
                depthRaw = raw,
                depthOffset = offset,
                lastNmeaMillis = System.currentTimeMillis()
            )
        }
    }

    private fun parseIiHdg(fields: List<String>) {
        val magnetic =
            fields.getOrNull(1)?.toDoubleOrNull()
                ?: return

        val variation =
            fields.getOrNull(4)?.toDoubleOrNull()

        val direction =
            fields.getOrNull(5)

        val signedVariation = when {
            variation == null -> 0.0
            direction == "W" -> -variation
            direction == "E" -> variation
            else -> 0.0
        }

        NmeaStateStore.update { old ->
            val heading =
                normaliseDegrees(magnetic + signedVariation)

            old.copy(
                trueHeading = heading,
                trueWindDirection =
                    calculateWindDirection(
                        heading,
                        old.trueWindAngle
                    ),
                lastNmeaMillis = System.currentTimeMillis()
            )
        }
    }

    private fun parseIiMwv(fields: List<String>) {
        if (
            fields.getOrNull(2) != "T" ||
            fields.getOrNull(5) != "A"
        ) {
            return
        }

        val angle =
            fields.getOrNull(1)?.toDoubleOrNull()
                ?: return

        val speed =
            fields.getOrNull(3)?.toDoubleOrNull()
                ?: return

        val knots = when (fields.getOrNull(4)) {
            "N" -> speed
            "M" -> speed * 1.943844
            "K" -> speed * 0.539957
            else -> return
        }

        NmeaStateStore.update { old ->
            val trueAngle = normaliseDegrees(angle)

            old.copy(
                trueWindAngle = trueAngle,
                trueWindSpeedKnots = knots,
                trueWindDirection =
                    calculateWindDirection(
                        old.trueHeading,
                        trueAngle
                    ),
                lastNmeaMillis = System.currentTimeMillis()
            )
        }
    }

    private fun parseIiVhw(fields: List<String>) {
        val stw =
            fields.getOrNull(5)?.toDoubleOrNull()
                ?: return

        NmeaStateStore.update {
            it.copy(
                stwKnots = stw,
                lastNmeaMillis = System.currentTimeMillis()
            )
        }
    }

    private fun parseRsa(fields: List<String>) {
        val starboard =
            fields.getOrNull(1)?.toDoubleOrNull()
        val starboardStatus = fields.getOrNull(2)

        val port =
            fields.getOrNull(3)?.toDoubleOrNull()
        val portStatus = fields.getOrNull(4)

        val rudder = when {
            starboard != null && starboardStatus == "A" ->
                starboard
            port != null && portStatus == "A" ->
                -port
            else -> null
        } ?: return

        NmeaStateStore.update {
            it.copy(
                rudderDegrees = rudder,
                lastNmeaMillis = System.currentTimeMillis()
            )
        }
    }

    private fun parseXdr(fields: List<String>) {
        var yaw: Double? = null
        var pitch: Double? = null
        var roll: Double? = null

        var i = 1
        while (i + 3 < fields.size) {
            val value =
                fields[i + 1].toDoubleOrNull()
            val name = fields[i + 3]

            when (name) {
                "Yaw" -> yaw = value
                "Pitch" -> pitch = value
                "Roll" -> roll = value
            }

            i += 4
        }

        NmeaStateStore.update { old ->
            old.copy(
                yawDegrees = yaw ?: old.yawDegrees,
                pitchDegrees = pitch ?: old.pitchDegrees,
                rollDegrees = roll ?: old.rollDegrees,
                lastNmeaMillis = System.currentTimeMillis()
            )
        }
    }

    private fun parseRmb(fields: List<String>) {
        val distance =
            fields.getOrNull(10)?.toDoubleOrNull()
                ?: return

        NmeaStateStore.update {
            it.copy(
                distanceToWaypointNm = distance,
                lastNmeaMillis = System.currentTimeMillis()
            )
        }
    }

    private fun parseApb(fields: List<String>) {
        val commanded =
            fields.getOrNull(13)?.toDoubleOrNull()
                ?: return

        NmeaStateStore.update {
            it.copy(
                commandedCourseDegrees = commanded,
                lastNmeaMillis = System.currentTimeMillis()
            )
        }
    }

    private fun calculateWindDirection(
        heading: Double?,
        angle: Double?
    ): Double? {
        return if (heading != null && angle != null) {
            normaliseDegrees(heading + angle)
        } else {
            null
        }
    }

    private fun coordinateToDecimal(
        value: String,
        hemisphere: String
    ): Double? {
        val raw = value.toDoubleOrNull() ?: return null
        val degrees = floor(raw / 100.0)
        val minutes = raw - degrees * 100.0
        var result = degrees + minutes / 60.0

        if (hemisphere == "S" || hemisphere == "W") {
            result = -result
        }

        return result
    }

    private fun formatCoordinate(
        value: String,
        hemisphere: String
    ): String {
        val raw = value.toDoubleOrNull() ?: return "—"
        val degrees = floor(raw / 100.0).toInt()
        val minutes = raw - degrees * 100.0

        return String.format(
            Locale.US,
            "%02d° %05.2f′ %s",
            degrees,
            minutes,
            hemisphere
        )
    }

    private fun normaliseDegrees(value: Double): Double {
        return ((value % 360.0) + 360.0) % 360.0
    }

    private fun isChecksumValid(sentence: String): Boolean {
        val star = sentence.indexOf('*')
        if (star < 0 || star + 2 >= sentence.length) {
            return false
        }

        val expected =
            sentence.substring(star + 1, star + 3)
                .toIntOrNull(16)
                ?: return false

        var calculated = 0

        for (i in 1 until star) {
            calculated =
                calculated xor sentence[i].code
        }

        return calculated == expected
    }

    private fun appendWindCsv(point: HistoryPoint) {
        val direction = point.windDirection ?: return
        val speed = point.windSpeed ?: return

        val dateFormat =
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat =
            SimpleDateFormat("HH:mm:ss", Locale.US)

        dateFormat.timeZone = TimeZone.getDefault()
        timeFormat.timeZone = TimeZone.getDefault()

        val date = Date(point.timestamp)
        val dateText = dateFormat.format(date)
        val timeText = timeFormat.format(date)
        val fileName = "MM-$dateText.csv"

        val uri = getOrCreateCsvUri(fileName)

        contentResolver.openOutputStream(uri, "wa")?.use {
            OutputStreamWriter(
                it,
                StandardCharsets.UTF_8
            ).use { writer ->
                writer.append(dateText)
                writer.append(',')
                writer.append(timeText)
                writer.append(',')
                writer.append(
                    String.format(
                        Locale.US,
                        "%.1f",
                        direction
                    )
                )
                writer.append(',')
                writer.append(
                    String.format(
                        Locale.US,
                        "%.1f",
                        speed
                    )
                )
                writer.append('\n')
            }
        }
    }

    private fun getOrCreateCsvUri(fileName: String): Uri {
        csvUriCache[fileName]?.let { return it }

        val relativePath =
            "${Environment.DIRECTORY_DOWNLOADS}/$CSV_FOLDER/"

        val projection =
            arrayOf(MediaStore.Downloads._ID)

        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Downloads.RELATIVE_PATH} = ?"

        val args =
            arrayOf(fileName, relativePath)

        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val uri = Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0).toString()
                )

                csvUriCache[fileName] = uri
                return uri
            }
        }

        val values = ContentValues().apply {
            put(
                MediaStore.Downloads.DISPLAY_NAME,
                fileName
            )
            put(
                MediaStore.Downloads.MIME_TYPE,
                "text/csv"
            )
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                relativePath
            )
        }

        val uri =
            contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("CSV-bestand kon niet worden gemaakt")

        contentResolver.openOutputStream(uri, "w")?.use {
            OutputStreamWriter(
                it,
                StandardCharsets.UTF_8
            ).use { writer ->
                writer.write(
                    "datum,tijd,windrichting_graden," +
                        "ware_windsnelheid_kn\n"
                )
            }
        }

        csvUriCache[fileName] = uri
        return uri
    }
}
