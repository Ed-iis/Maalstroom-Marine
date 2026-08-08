package nl.maalstroom.marine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.floor

class MainActivity : AppCompatActivity() {

    companion object {
        private const val UDP_PORT = 10110
        private const val MAX_PACKET_SIZE = 65_535
        private const val SAMPLE_INTERVAL_MS = 10_000L
        private const val CSV_FOLDER = "Maalstroom Marine"
    }

    private lateinit var webView: WebView
    private val receiverRunning = AtomicBoolean(false)
    private var receiverSocket: DatagramSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var webViewReady = false

    private val stateLock = Any()
    private val state = MarineState()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val csvUriCache = mutableMapOf<String, Uri>()

    private val sampler = object : Runnable {
        override fun run() {
            sampleCurrentData()
            mainHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            setBackgroundColor(0xFF06141F.toInt())

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    webViewReady = true
                    publishState()
                }
            }

            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
        acquireWakeLock()
        startUdpReceiver()
        mainHandler.post(sampler)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(sampler)
        stopUdpReceiver()
        wakeLock?.let { if (it.isHeld) it.release() }
        webView.destroy()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MaalstroomMarine::NmeaReceiver"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startUdpReceiver() {
        if (!receiverRunning.compareAndSet(false, true)) return

        thread(name = "NMEA-UDP-Receiver", isDaemon = true) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress("0.0.0.0", UDP_PORT))
                }
                receiverSocket = socket

                val packetBuffer = ByteArray(MAX_PACKET_SIZE)
                val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                val lineBuffer = StringBuilder()

                while (receiverRunning.get()) {
                    packet.length = packetBuffer.size
                    socket.receive(packet)

                    val text = String(
                        packet.data,
                        packet.offset,
                        packet.length,
                        StandardCharsets.US_ASCII
                    )

                    lineBuffer.append(text.replace('\r', '\n'))
                    val complete = lineBuffer.toString().split('\n')
                    lineBuffer.clear()

                    if (!text.endsWith("\n") && !text.endsWith("\r")) {
                        lineBuffer.append(complete.lastOrNull().orEmpty())
                    }

                    val limit =
                        if (lineBuffer.isNotEmpty()) complete.size - 1
                        else complete.size

                    for (index in 0 until limit) {
                        val sentence = complete[index].trim()
                        if (sentence.isNotEmpty()) parseSentence(sentence)
                    }
                }
            } catch (error: SocketException) {
                if (receiverRunning.get()) {
                    sendStatus(false, "UDP-fout: ${error.message}")
                }
            } catch (error: Exception) {
                sendStatus(false, "Fout: ${error.message}")
            } finally {
                receiverSocket?.close()
                receiverSocket = null
                receiverRunning.set(false)
            }
        }
    }

    private fun stopUdpReceiver() {
        receiverRunning.set(false)
        receiverSocket?.close()
        receiverSocket = null
    }

    private fun parseSentence(sentence: String) {
        if (!sentence.startsWith("$") || !isChecksumValid(sentence)) return

        val body = sentence.substring(1, sentence.indexOf('*'))
        val fields = body.split(',')
        if (fields.isEmpty()) return

        val talkerAndType = fields[0]
        val talker = talkerAndType.take(2)
        val sentenceType = talkerAndType.takeLast(3)

        when (sentenceType) {
            "RMC" -> parseRmc(fields)
            "DPT" -> parseDpt(fields)
            "HDG" -> if (talker == "II") parseIiHdg(fields)
            "MWV" -> if (talker == "II") parseIiMwv(fields)
        }
    }

    private fun parseRmc(fields: List<String>) {
        if (fields.size < 7 || fields[2] != "A") return

        val latRaw = fields[3]
        val latHem = fields[4]
        val lonRaw = fields[5]
        val lonHem = fields[6]

        val lat = coordinateToDecimal(latRaw, latHem) ?: return
        val lon = coordinateToDecimal(lonRaw, lonHem) ?: return

        synchronized(stateLock) {
            state.latitudeDisplay = formatCoordinate(latRaw, latHem)
            state.longitudeDisplay = formatCoordinate(lonRaw, lonHem)
            state.latitudeDecimal = lat
            state.longitudeDecimal = lon
        }
        publishState()
    }

    private fun parseDpt(fields: List<String>) {
        if (fields.size < 2) return

        val raw = fields[1].toDoubleOrNull() ?: return
        val offset = fields.getOrNull(2)?.toDoubleOrNull() ?: 0.0

        synchronized(stateLock) {
            state.depthRaw = raw
            state.depthOffset = offset
        }
        publishState()
    }

    private fun parseIiHdg(fields: List<String>) {
        if (fields.size < 2) return

        val magnetic = fields[1].toDoubleOrNull() ?: return
        val variation = fields.getOrNull(4)?.toDoubleOrNull()
        val direction = fields.getOrNull(5)

        val signedVariation = when {
            variation == null -> 0.0
            direction == "W" -> -variation
            direction == "E" -> variation
            else -> 0.0
        }

        synchronized(stateLock) {
            state.trueHeading = normaliseDegrees(magnetic + signedVariation)
            calculateTrueWindDirectionLocked()
        }
        publishState()
    }

    private fun parseIiMwv(fields: List<String>) {
        if (fields.size < 6) return
        if (fields[2] != "T" || fields[5] != "A") return

        val angle = fields[1].toDoubleOrNull() ?: return
        val speed = fields[3].toDoubleOrNull() ?: return

        val knots = when (fields[4]) {
            "N" -> speed
            "M" -> speed * 1.943844
            "K" -> speed * 0.539957
            else -> return
        }

        synchronized(stateLock) {
            state.trueWindAngle = normaliseDegrees(angle)
            state.trueWindSpeedKnots = knots
            calculateTrueWindDirectionLocked()
        }
        publishState()
    }

    private fun calculateTrueWindDirectionLocked() {
        val heading = state.trueHeading
        val angle = state.trueWindAngle
        state.trueWindDirection =
            if (heading != null && angle != null) {
                normaliseDegrees(heading + angle)
            } else null
    }

    private fun publishState() {
        if (!webViewReady) return

        val json = synchronized(stateLock) {
            JSONObject().apply {
                put("connected", true)
                put("statusText", "Live NMEA")

                put("latitudeDisplay", state.latitudeDisplay)
                put("longitudeDisplay", state.longitudeDisplay)
                put("positionAvailable", state.latitudeDecimal != null)

                state.depthRaw?.let { put("depthRaw", it) }
                put("depthOffset", state.depthOffset)
                put("depthAvailable", state.depthRaw != null)

                state.trueWindDirection?.let { put("trueWindDirection", it) }
                state.trueWindSpeedKnots?.let { put("trueWindSpeed", it) }
                put(
                    "windAvailable",
                    state.trueWindDirection != null &&
                        state.trueWindSpeedKnots != null
                )

                put("lastUpdateMillis", System.currentTimeMillis())
            }.toString()
        }

        runOnUiThread {
            webView.evaluateJavascript(
                "window.updateMarineData($json);",
                null
            )
        }
    }

    private fun sendStatus(connected: Boolean, message: String) {
        if (!webViewReady) return

        val json = JSONObject().apply {
            put("connected", connected)
            put("statusText", message)
            put("lastUpdateMillis", System.currentTimeMillis())
        }.toString()

        runOnUiThread {
            webView.evaluateJavascript(
                "window.updateMarineData($json);",
                null
            )
        }
    }

    private fun sampleCurrentData() {
        val now = System.currentTimeMillis()

        val snapshot = synchronized(stateLock) {
            SampleSnapshot(
                timestamp = now,
                windDirection = state.trueWindDirection,
                windSpeed = state.trueWindSpeedKnots,
                depth = state.depthRaw?.plus(state.depthOffset)
            )
        }

        sendSampleToWebView(snapshot)

        if (snapshot.windDirection != null && snapshot.windSpeed != null) {
            thread(name = "Wind-CSV-Logger", isDaemon = true) {
                try {
                    appendWindPointToCsv(snapshot)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendSampleToWebView(snapshot: SampleSnapshot) {
        if (!webViewReady) return

        val json = JSONObject().apply {
            put("timestamp", snapshot.timestamp)
            snapshot.windDirection?.let { put("windDirection", it) }
            snapshot.windSpeed?.let { put("windSpeed", it) }
            snapshot.depth?.let { put("depth", it) }
        }.toString()

        runOnUiThread {
            webView.evaluateJavascript(
                "window.addHistoryPoint($json);",
                null
            )
        }
    }

    private fun appendWindPointToCsv(snapshot: SampleSnapshot) {
        val windDirection = snapshot.windDirection ?: return
        val windSpeed = snapshot.windSpeed ?: return

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        dateFormat.timeZone = TimeZone.getDefault()
        timeFormat.timeZone = TimeZone.getDefault()

        val date = Date(snapshot.timestamp)
        val dateText = dateFormat.format(date)
        val timeText = timeFormat.format(date)
        val fileName = "MM-$dateText.csv"

        val uri = getOrCreateCsvUri(fileName)
        val stream = contentResolver.openOutputStream(uri, "wa") ?: return

        OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
            writer.append(dateText)
            writer.append(',')
            writer.append(timeText)
            writer.append(',')
            writer.append(String.format(Locale.US, "%.1f", windDirection))
            writer.append(',')
            writer.append(String.format(Locale.US, "%.1f", windSpeed))
            writer.append('\n')
        }
    }

    private fun getOrCreateCsvUri(fileName: String): Uri {
        csvUriCache[fileName]?.let { return it }

        val relativePath =
            "${Environment.DIRECTORY_DOWNLOADS}/$CSV_FOLDER/"

        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val args = arrayOf(fileName, relativePath)

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
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
        }

        val uri = contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("CSV-bestand kon niet worden gemaakt")

        contentResolver.openOutputStream(uri, "w")?.use {
            OutputStreamWriter(it, StandardCharsets.UTF_8).use { writer ->
                writer.write(
                    "datum,tijd,windrichting_graden," +
                        "ware_windsnelheid_kn\n"
                )
            }
        }

        csvUriCache[fileName] = uri
        return uri
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
        if (star < 0 || star + 2 >= sentence.length) return false

        val expected = sentence
            .substring(star + 1, star + 3)
            .toIntOrNull(16) ?: return false

        var calculated = 0
        for (index in 1 until star) {
            calculated = calculated xor sentence[index].code
        }
        return calculated == expected
    }

    private data class MarineState(
        var latitudeDisplay: String = "—",
        var longitudeDisplay: String = "—",
        var latitudeDecimal: Double? = null,
        var longitudeDecimal: Double? = null,
        var depthRaw: Double? = null,
        var depthOffset: Double = 0.0,
        var trueHeading: Double? = null,
        var trueWindAngle: Double? = null,
        var trueWindDirection: Double? = null,
        var trueWindSpeedKnots: Double? = null
    )

    private data class SampleSnapshot(
        val timestamp: Long,
        val windDirection: Double?,
        val windSpeed: Double?,
        val depth: Double?
    )
}
