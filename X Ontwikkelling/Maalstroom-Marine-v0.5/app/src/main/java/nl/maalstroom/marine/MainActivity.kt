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
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.floor

class MainActivity : AppCompatActivity() {
    companion object { private const val UDP_PORT = 10110; private const val MAX_PACKET_SIZE = 65_535; private const val LOG_INTERVAL_MS = 10_000L; private const val CSV_FOLDER = "Maalstroom Marine" }
    private lateinit var webView: WebView
    private val receiverRunning = AtomicBoolean(false)
    private var receiverSocket: DatagramSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val stateLock = Any()
    private val marineState = MarineState()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val csvUriCache = mutableMapOf<String, Uri>()
    private val windLogger = object : Runnable {
        override fun run() { logCurrentWindPoint(); mainHandler.postDelayed(this, LOG_INTERVAL_MS) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.domStorageEnabled = true; settings.allowFileAccess = true; settings.allowContentAccess = true
            setBackgroundColor(0xFF06141F.toInt()); loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView); acquireWakeLock(); startUdpReceiver(); mainHandler.post(windLogger)
    }

    override fun onDestroy() { mainHandler.removeCallbacks(windLogger); stopUdpReceiver(); wakeLock?.let { if (it.isHeld) it.release() }; webView.destroy(); super.onDestroy() }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MaalstroomMarine::NmeaReceiver").apply { setReferenceCounted(false); acquire() }
    }

    private fun startUdpReceiver() {
        if (!receiverRunning.compareAndSet(false, true)) return
        thread(name = "NMEA-UDP-Receiver", isDaemon = true) {
            try {
                val socket = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress("0.0.0.0", UDP_PORT)) }
                receiverSocket = socket; sendConnectionStatus(true, "Luistert op UDP $UDP_PORT")
                val packetBuffer = ByteArray(MAX_PACKET_SIZE); val packet = DatagramPacket(packetBuffer, packetBuffer.size); val lineBuffer = StringBuilder()
                while (receiverRunning.get()) {
                    packet.length = packetBuffer.size; socket.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII)
                    lineBuffer.append(text.replace('\r', '\n')); val complete = lineBuffer.toString().split('\n'); lineBuffer.clear()
                    if (!text.endsWith("\n") && !text.endsWith("\r")) lineBuffer.append(complete.lastOrNull().orEmpty())
                    val limit = if (lineBuffer.isNotEmpty()) complete.size - 1 else complete.size
                    for (i in 0 until limit) complete[i].trim().takeIf { it.isNotEmpty() }?.let(::parseSentence)
                }
            } catch (e: SocketException) { if (receiverRunning.get()) sendConnectionStatus(false, "UDP-fout: ${e.message}") }
              catch (e: Exception) { sendConnectionStatus(false, "Fout: ${e.message}") }
            finally { receiverSocket?.close(); receiverSocket = null; receiverRunning.set(false) }
        }
    }

    private fun stopUdpReceiver() { receiverRunning.set(false); receiverSocket?.close(); receiverSocket = null }

    private fun parseSentence(sentence: String) {
        if (!sentence.startsWith("$") || !isChecksumValid(sentence)) return
        val fields = sentence.substring(1, sentence.indexOf('*')).split(','); if (fields.isEmpty()) return
        val talker = fields[0].take(2); val type = fields[0].takeLast(3)
        when (type) {
            "RMC" -> parseRmc(fields)
            "DPT" -> parseDpt(fields)
            "HDG" -> if (talker == "II") parseIiHdg(fields)
            "MWV" -> if (talker == "II") parseIiMwv(fields)
        }
    }

    private fun parseRmc(f: List<String>) {
        if (f.size < 7 || f[2] != "A") return
        val lat = coordinateToDecimal(f[3], f[4]) ?: return; val lon = coordinateToDecimal(f[5], f[6]) ?: return
        synchronized(stateLock) { marineState.latitudeDisplay = formatCoordinate(f[3], f[4]); marineState.longitudeDisplay = formatCoordinate(f[5], f[6]); marineState.latitudeDecimal = lat; marineState.longitudeDecimal = lon }
        publishState()
    }

    private fun parseDpt(f: List<String>) {
        val depth = f.getOrNull(1)?.toDoubleOrNull() ?: return; val offset = f.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        synchronized(stateLock) { marineState.depthRaw = depth; marineState.depthOffset = offset }; publishState()
    }

    private fun parseIiHdg(f: List<String>) {
        val magnetic = f.getOrNull(1)?.toDoubleOrNull() ?: return
        val variation = f.getOrNull(4)?.toDoubleOrNull() ?: 0.0
        val signedVariation = when (f.getOrNull(5)) { "W" -> -variation; "E" -> variation; else -> 0.0 }
        synchronized(stateLock) { marineState.trueHeading = normaliseDegrees(magnetic + signedVariation); calculateTrueWindDirectionLocked() }
        publishState()
    }

    private fun parseIiMwv(f: List<String>) {
        if (f.size < 6 || f[2] != "T" || f[5] != "A") return
        val angle = f[1].toDoubleOrNull() ?: return; val speed = f[3].toDoubleOrNull() ?: return
        val knots = when (f[4]) { "N" -> speed; "M" -> speed * 1.943844; "K" -> speed * 0.539957; else -> return }
        synchronized(stateLock) { marineState.trueWindAngle = normaliseDegrees(angle); marineState.trueWindSpeedKnots = knots; calculateTrueWindDirectionLocked() }
        publishState()
    }

    private fun calculateTrueWindDirectionLocked() {
        val h = marineState.trueHeading; val a = marineState.trueWindAngle
        marineState.trueWindDirection = if (h != null && a != null) normaliseDegrees(h + a) else null
    }

    private fun publishState() {
        val json = synchronized(stateLock) { JSONObject().apply {
            put("connected", true); put("statusText", "Live NMEA")
            put("latitudeDisplay", marineState.latitudeDisplay); put("longitudeDisplay", marineState.longitudeDisplay); put("positionAvailable", marineState.latitudeDecimal != null)
            marineState.depthRaw?.let { put("depthRaw", it) }; put("depthOffset", marineState.depthOffset); put("depthAvailable", marineState.depthRaw != null)
            marineState.trueWindDirection?.let { put("trueWindDirection", it) }; marineState.trueWindSpeedKnots?.let { put("trueWindSpeed", it) }
            put("windAvailable", marineState.trueWindDirection != null && marineState.trueWindSpeedKnots != null); put("lastUpdateMillis", System.currentTimeMillis())
        }.toString() }
        runOnUiThread { webView.evaluateJavascript("window.updateMarineData($json);", null) }
    }

    private fun sendConnectionStatus(connected: Boolean, message: String) {
        val json = JSONObject().apply { put("connected", connected); put("statusText", message); put("lastUpdateMillis", System.currentTimeMillis()) }.toString()
        runOnUiThread { webView.evaluateJavascript("window.updateMarineData($json);", null) }
    }

    private fun logCurrentWindPoint() {
        val snapshot = synchronized(stateLock) {
            val d = marineState.trueWindDirection; val s = marineState.trueWindSpeedKnots
            if (d == null || s == null) null else WindSnapshot(System.currentTimeMillis(), d, s)
        } ?: return
        val json = JSONObject().apply { put("timestamp", snapshot.timestamp); put("direction", snapshot.direction); put("speed", snapshot.speedKnots) }.toString()
        runOnUiThread { webView.evaluateJavascript("window.addWindHistoryPoint($json);", null) }
        thread(name = "Wind-CSV-Logger", isDaemon = true) { try { appendWindPointToCsv(snapshot) } catch (_: Exception) {} }
    }

    private fun appendWindPointToCsv(snapshot: WindSnapshot) {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val tf = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val date = Date(snapshot.timestamp); val dateText = df.format(date); val timeText = tf.format(date)
        val uri = getOrCreateCsvUri("MM-$dateText.csv")
        contentResolver.openOutputStream(uri, "wa")?.use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { w ->
                w.append(dateText).append(',').append(timeText).append(',')
                w.append(String.format(Locale.US, "%.1f", snapshot.direction)).append(',')
                w.append(String.format(Locale.US, "%.1f", snapshot.speedKnots)).append('\n')
            }
        }
    }

    private fun getOrCreateCsvUri(fileName: String): Uri {
        csvUriCache[fileName]?.let { return it }
        val rel = "${Environment.DIRECTORY_DOWNLOADS}/$CSV_FOLDER/"
        contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(fileName, rel), null
        )?.use { c -> if (c.moveToFirst()) {
            val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            csvUriCache[fileName] = uri; return uri
        }}
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName); put(MediaStore.Downloads.MIME_TYPE, "text/csv"); put(MediaStore.Downloads.RELATIVE_PATH, rel)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("CSV-bestand kon niet worden aangemaakt")
        contentResolver.openOutputStream(uri, "w")?.use { stream -> OutputStreamWriter(stream, StandardCharsets.UTF_8).use { it.write("datum,tijd,windrichting_graden,ware_windsnelheid_kn\n") } }
        csvUriCache[fileName] = uri; return uri
    }

    private fun coordinateToDecimal(value: String, hemisphere: String): Double? {
        val raw = value.toDoubleOrNull() ?: return null; val degrees = floor(raw / 100.0); val minutes = raw - degrees * 100.0
        var decimal = degrees + minutes / 60.0; if (hemisphere == "S" || hemisphere == "W") decimal = -decimal; return decimal
    }

    private fun formatCoordinate(value: String, hemisphere: String): String {
        val raw = value.toDoubleOrNull() ?: return "—"; val degrees = floor(raw / 100.0).toInt(); val minutes = raw - degrees * 100.0
        return String.format(Locale.US, "%02d° %05.2f′ %s", degrees, minutes, hemisphere)
    }

    private fun normaliseDegrees(v: Double) = ((v % 360.0) + 360.0) % 360.0

    private fun isChecksumValid(sentence: String): Boolean {
        val star = sentence.indexOf('*'); if (star < 0 || star + 2 >= sentence.length) return false
        val expected = sentence.substring(star + 1, star + 3).toIntOrNull(16) ?: return false
        var calculated = 0; for (i in 1 until star) calculated = calculated xor sentence[i].code; return calculated == expected
    }

    private data class WindSnapshot(val timestamp: Long, val direction: Double, val speedKnots: Double)

    private data class MarineState(
        var latitudeDisplay: String = "—", var longitudeDisplay: String = "—", var latitudeDecimal: Double? = null, var longitudeDecimal: Double? = null,
        var depthRaw: Double? = null, var depthOffset: Double = 0.0,
        var trueHeading: Double? = null, var trueWindAngle: Double? = null, var trueWindDirection: Double? = null, var trueWindSpeedKnots: Double? = null
    )
}
