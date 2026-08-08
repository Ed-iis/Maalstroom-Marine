package nl.maalstroom.marine

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val UI_REFRESH_MS = 500L
        private const val HISTORY_REFRESH_MS = 10_000L
        private const val HISTORY_WINDOW_MS =
            24L * 60L * 60L * 1000L
    }

    private lateinit var webView: WebView
    private lateinit var database: MarineDatabase
    private val handler = Handler(Looper.getMainLooper())

    private var webViewReady = false
    private var lastHistoryTimestamp = 0L

    private val uiUpdater = object : Runnable {
        override fun run() {
            publishCurrentState()
            handler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    private val historyUpdater = object : Runnable {
        override fun run() {
            publishNewHistory()
            handler.postDelayed(this, HISTORY_REFRESH_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = MarineDatabase(this)

        ContextCompat.startForegroundService(
            this,
            Intent(this, NmeaBackgroundService::class.java)
        )

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            setBackgroundColor(0xFF06141F.toInt())

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    webViewReady = true
                    loadHistory()
                    publishCurrentState()
                }
            }

            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)

        handler.post(uiUpdater)
        handler.post(historyUpdater)
    }

    override fun onResume() {
        super.onResume()

        if (webViewReady) {
            loadHistory()
            publishCurrentState()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(uiUpdater)
        handler.removeCallbacks(historyUpdater)
        database.close()
        webView.destroy()
        super.onDestroy()
    }

    private fun publishCurrentState() {
        if (!webViewReady) return

        val state = NmeaStateStore.snapshot()
        val connected =
            state.lastNmeaMillis > 0L &&
                System.currentTimeMillis() -
                state.lastNmeaMillis < 5_000L

        val json = JSONObject().apply {
            put("connected", connected)
            put(
                "statusText",
                if (connected) "Live NMEA"
                else "Wachten op NMEA…"
            )

            put("latitudeDisplay", state.latitudeDisplay)
            put("longitudeDisplay", state.longitudeDisplay)
            put(
                "positionAvailable",
                state.positionAvailable
            )

            state.depthRaw?.let { put("depthRaw", it) }
            put("depthOffset", state.depthOffset)
            put("depthAvailable", state.depthRaw != null)

            state.trueWindDirection?.let {
                put("trueWindDirection", it)
            }

            state.trueWindSpeedKnots?.let {
                put("trueWindSpeed", it)
            }

            put(
                "windAvailable",
                state.trueWindDirection != null &&
                    state.trueWindSpeedKnots != null
            )

            state.trueHeading?.let { put("heading", it) }
            state.cogDegrees?.let { put("cog", it) }
            state.stwKnots?.let { put("stw", it) }
            state.sogKnots?.let { put("sog", it) }
            state.rudderDegrees?.let { put("rudder", it) }
            state.pitchDegrees?.let { put("pitch", it) }
            state.rollDegrees?.let { put("roll", it) }
            state.yawDegrees?.let { put("yaw", it) }

            state.distanceToWaypointNm?.let {
                put("distanceToWaypoint", it)
            }

            state.commandedCourseDegrees?.let {
                put("commandedCourse", it)
            }

            state.autopilotMode?.let {
                put("autopilotMode", it)
            }

            put(
                "lastUpdateMillis",
                if (state.lastNmeaMillis > 0)
                    state.lastNmeaMillis
                else System.currentTimeMillis()
            )
        }.toString()

        webView.evaluateJavascript(
            "window.updateMarineData($json);",
            null
        )
    }

    private fun loadHistory() {
        if (!webViewReady) return

        Thread {
            val since =
                System.currentTimeMillis() - HISTORY_WINDOW_MS
            val points = database.readSince(since)

            lastHistoryTimestamp =
                points.lastOrNull()?.timestamp ?: 0L

            val array = JSONArray()

            for (point in points) {
                array.put(historyPointToJson(point))
            }

            runOnUiThread {
                if (webViewReady) {
                    webView.evaluateJavascript(
                        "window.replaceHistory($array);",
                        null
                    )
                }
            }
        }.start()
    }

    private fun publishNewHistory() {
        if (!webViewReady) return

        Thread {
            val since =
                if (lastHistoryTimestamp > 0L) {
                    lastHistoryTimestamp + 1L
                } else {
                    System.currentTimeMillis() -
                        HISTORY_WINDOW_MS
                }

            val points = database.readSince(since)

            if (points.isNotEmpty()) {
                lastHistoryTimestamp =
                    points.last().timestamp

                val array = JSONArray()

                for (point in points) {
                    array.put(historyPointToJson(point))
                }

                runOnUiThread {
                    if (webViewReady) {
                        webView.evaluateJavascript(
                            "window.appendHistory($array);",
                            null
                        )
                    }
                }
            }
        }.start()
    }

    private fun historyPointToJson(
        point: HistoryPoint
    ): JSONObject {
        return JSONObject().apply {
            put("timestamp", point.timestamp)
            point.depth?.let { put("depth", it) }
            point.windDirection?.let {
                put("windDirection", it)
            }
            point.windSpeed?.let {
                put("windSpeed", it)
            }
        }
    }
}
