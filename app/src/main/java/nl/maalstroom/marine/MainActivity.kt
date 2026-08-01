package nl.maalstroom.marine

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.PowerManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.floor

class MainActivity : AppCompatActivity() {

    companion object {
        private const val UDP_PORT = 10110
        private const val MAX_PACKET_SIZE = 65_535
    }

    private lateinit var webView: WebView
    private val receiverRunning = AtomicBoolean(false)
    private var receiverSocket: DatagramSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val stateLock = Any()
    private val marineState = MarineState()

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
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
        acquireWakeLock()
        startUdpReceiver()
    }

    override fun onDestroy() {
        stopUdpReceiver()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
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

                sendConnectionStatus(true, "Luistert op UDP $UDP_PORT")

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

                    val limit = if (lineBuffer.isNotEmpty()) complete.size - 1 else complete.size
                    for (index in 0 until limit) {
                        val sentence = complete[index].trim()
                        if (sentence.isNotEmpty()) parseSentence(sentence)
                    }
                }
            } catch (error: SocketException) {
                if (receiverRunning.get()) {
                    sendConnectionStatus(false, "UDP-fout: ${error.message}")
                }
            } catch (error: Exception) {
                sendConnectionStatus(false, "Fout: ${error.message}")
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

        when (fields[0].takeLast(3)) {
            "RMC" -> parseRmc(fields)
            "DPT" -> parseDpt(fields)
        }
    }

    private fun parseRmc(fields: List<String>) {
        // $GPRMC,time,status,latitude,N/S,longitude,E/W,sog,cog,date,...
        if (fields.size < 7 || fields[2] != "A") return

        val latitudeRaw = fields[3]
        val latitudeHemisphere = fields[4]
        val longitudeRaw = fields[5]
        val longitudeHemisphere = fields[6]

        val latitude = coordinateToDecimal(latitudeRaw, latitudeHemisphere) ?: return
        val longitude = coordinateToDecimal(longitudeRaw, longitudeHemisphere) ?: return

        synchronized(stateLock) {
            marineState.latitudeDisplay =
                formatCoordinate(latitudeRaw, latitudeHemisphere, false)
            marineState.longitudeDisplay =
                formatCoordinate(longitudeRaw, longitudeHemisphere, true)
            marineState.latitudeDecimal = latitude
            marineState.longitudeDecimal = longitude
            marineState.lastPositionMillis = System.currentTimeMillis()
            marineState.lastSentence = fields.joinToString(",")
        }

        publishState()
    }

    private fun parseDpt(fields: List<String>) {
        // $IIDPT,diepte-onder-transducer,offset,*checksum
        if (fields.size < 2) return

        val depthRaw = fields[1].toDoubleOrNull() ?: return
        val offset = fields.getOrNull(2)?.toDoubleOrNull() ?: 0.0

        synchronized(stateLock) {
            marineState.depthRaw = depthRaw
            marineState.depthOffset = offset
            marineState.lastDepthMillis = System.currentTimeMillis()
            marineState.lastSentence = fields.joinToString(",")
        }

        publishState()
    }

    private fun publishState() {
        val json = synchronized(stateLock) {
            JSONObject().apply {
                put("connected", true)
                put("statusText", "Live NMEA")
                put("latitudeDisplay", marineState.latitudeDisplay)
                put("longitudeDisplay", marineState.longitudeDisplay)

                marineState.latitudeDecimal?.let { put("latitudeDecimal", it) }
                marineState.longitudeDecimal?.let { put("longitudeDecimal", it) }
                marineState.depthRaw?.let { put("depthRaw", it) }
                put("depthOffset", marineState.depthOffset)

                put("positionAvailable", marineState.latitudeDecimal != null)
                put("depthAvailable", marineState.depthRaw != null)
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

    private fun sendConnectionStatus(connected: Boolean, message: String) {
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

    private fun coordinateToDecimal(value: String, hemisphere: String): Double? {
        val raw = value.toDoubleOrNull() ?: return null
        val degrees = floor(raw / 100.0)
        val minutes = raw - degrees * 100.0
        var decimal = degrees + minutes / 60.0

        if (hemisphere == "S" || hemisphere == "W") {
            decimal = -decimal
        }
        return decimal
    }

    private fun formatCoordinate(
        value: String,
        hemisphere: String,
        longitude: Boolean
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

    private fun isChecksumValid(sentence: String): Boolean {
        val star = sentence.indexOf('*')
        if (star < 0 || star + 2 >= sentence.length) return false

        val expected = sentence.substring(star + 1, star + 3)
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
        var lastPositionMillis: Long = 0L,
        var lastDepthMillis: Long = 0L,
        var lastSentence: String = ""
    )
}
