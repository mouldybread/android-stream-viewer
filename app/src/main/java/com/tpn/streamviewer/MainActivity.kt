package com.tpn.streamviewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private val tag = "StreamViewer"
    private lateinit var webView: WebView
    private lateinit var ipTextView: TextView
    private lateinit var statusOverlay: FrameLayout
    private lateinit var errorTextView: TextView
    private var webServer: AndroidWebServer? = null
    private var currentPort = 9090

    private var tourCameras: List<CameraConfig> = emptyList()
    private val tourHandler = Handler(Looper.getMainLooper())
    private var tourActive = false
    private var tourDuration: Int = 10
    private var tourCurrentIndex: Int = 0

    // Burn-in protection vars
    private var burnInProtectionEnabled = true
    private val burnInHandler = Handler(Looper.getMainLooper())
    private val burnInInterval = 2 * 60 * 60 * 1000L // 2 hours
    private val burnInDuration = 60 * 1000L // 1 minute
    private var burnInBlankActive = false

    // Stream state
    private var currentGo2rtcUrl: String = ""
    private var currentStreamName: String = ""
    private var currentProtocol: String = "mse"
    private var pendingPlayRequest = false
    private var isFirstRun = true

    // Stream health monitoring
    private val streamHealthHandler = Handler(Looper.getMainLooper())
    private var lastStreamActivity = 0L
    private val streamTimeout = 15000L // 15 seconds
    private var streamHealthCheckActive = false
    private lateinit var prefs: SharedPreferences

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)

            webView = findViewById(R.id.webView)
            ipTextView = findViewById(R.id.ipTextView)
            statusOverlay = findViewById(R.id.statusOverlay)
            errorTextView = findViewById(R.id.errorTextView)

            prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            isFirstRun = !prefs.getBoolean("initialized", false)

            hideSystemUI()

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE

                // Secure local file access settings
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    try {
                        val logMsg = "[${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}] ${consoleMessage.message()}"
                        when (consoleMessage.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> Log.e(tag, "JS: $logMsg")
                            ConsoleMessage.MessageLevel.WARNING -> Log.w(tag, "JS: $logMsg")
                            else -> Log.d(tag, "JS: $logMsg")
                        }
                        webServer?.addLog(logMsg)
                    } catch (e: Exception) {
                        Log.e(tag, "Error logging console message", e)
                    }
                    return true
                }
            }

            // JavaScript interface to monitor stream health
            webView.addJavascriptInterface(object {
                @Suppress("unused")
                @JavascriptInterface
                fun onStreamPlaying() {
                    runOnUiThread {
                        lastStreamActivity = System.currentTimeMillis()
                        Log.d(tag, "Stream heartbeat received")
                    }
                }

                @Suppress("unused")
                @JavascriptInterface
                fun onStreamError(message: String) {
                    runOnUiThread {
                        Log.e(tag, "Stream error from JS: $message")
                        webServer?.addLog("Stream error: $message")

                        if (message.contains("PIPELINE_ERROR_DECODE") || message.contains("MEDIA_ERR_DECODE")) {
                            Log.w(tag, "Decode error detected, switching to software layer type")
                            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        }

                        checkStreamHealth()
                    }
                }
            }, "AndroidInterface")

            // Start web server first
            startWebServer()

            val delay = if (isFirstRun) 2000L else 1000L

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (!pendingPlayRequest && currentStreamName.isEmpty()) {
                        Log.d(tag, "Loading placeholder screen...")
                        showPlaceholder()
                    } else {
                        Log.d(tag, "Skipping placeholder - play request already in progress")
                    }

                    if (isFirstRun) {
                        synchronized(prefs) {
                            prefs.edit { putBoolean("initialized", true) }
                        }
                        Log.d(tag, "First run initialization complete")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error showing placeholder", e)
                }
            }, delay)

            Handler(Looper.getMainLooper()).postDelayed({
                startBurnInProtection()
            }, 5000)

            Handler(Looper.getMainLooper()).postDelayed({
                handleIntent(intent)
            }, 3000)

        } catch (e: Exception) {
            Log.e(tag, "CRITICAL ERROR in onCreate", e)
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val cameraName = it.getStringExtra("camera_name")
            if (cameraName != null) {
                Log.d(tag, "Intent received with camera_name: $cameraName")
                webServer?.addLog("Intent: Loading camera '$cameraName'")

                val cameras = loadCameras()
                val camera = cameras.find { cam ->
                    cam.name.equals(cameraName, ignoreCase = true) ||
                            cam.streamName.equals(cameraName, ignoreCase = true)
                }

                if (camera != null) {
                    val serverUrl = webServer?.getSavedGo2rtcUrl() ?: currentGo2rtcUrl
                    if (serverUrl.isNotEmpty()) {
                        Log.d(tag, "Playing camera: ${camera.name} (${camera.streamName})")
                        playStream(serverUrl, camera.streamName, camera.protocol)
                        Toast.makeText(this, "Loading: ${camera.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e(tag, "No go2rtc server URL configured")
                        Toast.makeText(this, "Error: No server URL configured", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.w(tag, "Camera not found: $cameraName")
                    Toast.makeText(this, "Camera not found: $cameraName", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startStreamHealthMonitoring() {
        if (streamHealthCheckActive) return
        streamHealthCheckActive = true
        lastStreamActivity = System.currentTimeMillis()

        streamHealthHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!streamHealthCheckActive) return

                val timeSinceActivity = System.currentTimeMillis() - lastStreamActivity
                if (timeSinceActivity > streamTimeout && currentStreamName.isNotEmpty()) {
                    Log.w(tag, "Stream appears frozen (${timeSinceActivity}ms since activity)")
                    webServer?.addLog("Stream timeout detected - attempting recovery")

                    playStream(currentGo2rtcUrl, currentStreamName, currentProtocol, forceFullReload = true)
                }

                streamHealthHandler.postDelayed(this, 10000)
            }
        }, 10000)
    }

    private fun stopStreamHealthMonitoring() {
        streamHealthCheckActive = false
        streamHealthHandler.removeCallbacksAndMessages(null)
    }

    private fun checkStreamHealth() {
        val timeSinceActivity = System.currentTimeMillis() - lastStreamActivity
        if (timeSinceActivity > streamTimeout) {
            Log.w(tag, "Stream health check failed")
            webServer?.addLog("Stream health check failed - reloading")
            playStream(currentGo2rtcUrl, currentStreamName, currentProtocol, forceFullReload = true)
        }
    }

    private fun cleanupWebView() {
        try {
            runOnUiThread {
                webView.stopLoading()
                Log.d(tag, "WebView softly cleaned up")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error cleaning up WebView", e)
        }
    }

    private fun startBurnInProtection() {
        val appPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        burnInProtectionEnabled = synchronized(appPrefs) {
            appPrefs.getBoolean("burn_in_protection", true)
        }

        if (!burnInProtectionEnabled) {
            Log.d(tag, "Burn-in protection disabled")
            return
        }

        Log.d(tag, "Burn-in protection enabled: 1 min blank every 2 hours")
        scheduleBurnInProtection()
    }

    private fun scheduleBurnInProtection() {
        if (!burnInProtectionEnabled) return

        burnInHandler.postDelayed({
            if (burnInProtectionEnabled && !tourActive && !burnInBlankActive) {
                triggerBurnInBlank()
            }
            scheduleBurnInProtection()
        }, burnInInterval)
    }

    private fun triggerBurnInBlank() {
        if (burnInBlankActive) return

        burnInBlankActive = true
        Log.d(tag, "Burn-in protection: Blanking screen for 1 minute")
        webServer?.addLog("Burn-in protection: Screen blanked")

        runOnUiThread {
            webView.loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
        }

        burnInHandler.postDelayed({
            burnInBlankActive = false
            Log.d(tag, "Burn-in protection: Restoring display")
            webServer?.addLog("Burn-in protection: Display restored")

            runOnUiThread {
                if (currentStreamName.isNotEmpty() && currentGo2rtcUrl.isNotEmpty()) {
                    playStream(currentGo2rtcUrl, currentStreamName, currentProtocol, forceFullReload = true)
                } else {
                    showPlaceholder()
                }
            }
        }, burnInDuration)
    }

    private fun setBurnInProtection(enabled: Boolean) {
        burnInProtectionEnabled = enabled
        val appPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        synchronized(appPrefs) {
            appPrefs.edit { putBoolean("burn_in_protection", enabled) }
        }

        Log.d(tag, "Burn-in protection ${if (enabled) "enabled" else "disabled"}")
        webServer?.addLog("Burn-in protection ${if (enabled) "enabled" else "disabled"}")

        if (enabled) {
            startBurnInProtection()
        } else {
            burnInHandler.removeCallbacksAndMessages(null)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startWebServer() {
        try {
            Log.d(tag, "Attempting to start server on port $currentPort")

            webServer = AndroidWebServer(
                currentPort,
                applicationContext,
                onStreamConfig = { go2rtcUrl, streamName, protocol ->
                    runOnUiThread {
                        try {
                            playStream(go2rtcUrl, streamName, protocol)
                        } catch (e: Exception) {
                            Log.e(tag, "Error in callback", e)
                        }
                    }
                },
                onTourStart = { cameras, duration ->
                    runOnUiThread {
                        try {
                            startTour(cameras, duration)
                        } catch (e: Exception) {
                            Log.e(tag, "Error starting tour", e)
                        }
                    }
                },
                onTourStop = {
                    runOnUiThread {
                        try {
                            stopTour()
                        } catch (e: Exception) {
                            Log.e(tag, "Error stopping tour", e)
                        }
                    }
                },
                getCameras = { loadCameras() },
                saveCameras = { cameras -> saveCameras(cameras) }
            )

            webServer?.setBurnInProtectionCallback { enabled ->
                runOnUiThread {
                    setBurnInProtection(enabled)
                }
            }

            webServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val ipAddress = getLocalIpAddress()
            Log.d(tag, "Server started on $ipAddress:$currentPort")

            runOnUiThread {
                ipTextView.text = ""
            }
        } catch (e: java.net.BindException) {
            Log.e(tag, "BindException - Port already in use", e)
            tryAlternativePort()
        } catch (e: IOException) {
            Log.e(tag, "IOException starting web server", e)
            Toast.makeText(this, "IO Error: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(tag, "Exception starting web server", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val intf = interfaces.nextElement()
                        val name = intf.name.lowercase()
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            val addrs = intf.inetAddresses
                            while (addrs.hasMoreElements()) {
                                val addr = addrs.nextElement()
                                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                                    return addr.hostAddress ?: "unknown"
                                }
                            }
                        }
                    }
                }
            }

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("dummy") ||
                    name.startsWith("docker") || name.startsWith("tailscale") || name.startsWith("p2p")
                ) {
                    continue
                }
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting IP address", e)
        }
        return "unknown"
    }

    private fun loadCameras(): List<CameraConfig> {
        try {
            val cameraPrefs = getSharedPreferences("cameras", Context.MODE_PRIVATE)
            val json = synchronized(cameraPrefs) {
                cameraPrefs.getString("camera_list", "[]") ?: "[]"
            }
            val jsonArray = JSONArray(json)
            val cameras = mutableListOf<CameraConfig>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                cameras.add(
                    CameraConfig(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        streamName = obj.getString("streamName"),
                        enabled = obj.getBoolean("enabled"),
                        protocol = obj.optString("protocol", "mse"),
                        order = obj.optInt("order", i)
                    )
                )
            }

            Log.d(tag, "Loaded ${cameras.size} cameras from storage")
            return cameras
        } catch (e: Exception) {
            Log.e(tag, "Error loading cameras", e)
            return emptyList()
        }
    }

    private fun saveCameras(cameras: List<CameraConfig>) {
        try {
            val cameraPrefs = getSharedPreferences("cameras", Context.MODE_PRIVATE)
            val jsonArray = JSONArray()

            cameras.forEach { cam ->
                jsonArray.put(
                    JSONObject().apply {
                        put("id", cam.id)
                        put("name", cam.name)
                        put("streamName", cam.streamName)
                        put("enabled", cam.enabled)
                        put("protocol", cam.protocol)
                        put("order", cam.order)
                    }
                )
            }

            synchronized(cameraPrefs) {
                cameraPrefs.edit { putString("camera_list", jsonArray.toString()) }
            }
            Log.d(tag, "Saved ${cameras.size} cameras to storage")
        } catch (e: Exception) {
            Log.e(tag, "Error saving cameras", e)
        }
    }

    private fun startTour(cameras: List<CameraConfig>, duration: Int) {
        if (cameras.isEmpty()) {
            Toast.makeText(this, "No cameras for tour", Toast.LENGTH_SHORT).show()
            return
        }

        stopTour()

        tourActive = true
        tourCameras = cameras
        tourDuration = duration
        tourCurrentIndex = 0

        Log.d(tag, "Starting tour with ${cameras.size} cameras, ${duration}s each")
        Toast.makeText(this, "Tour started: ${cameras.size} cameras", Toast.LENGTH_SHORT).show()

        playNextTourCamera()
    }

    private fun playNextTourCamera() {
        if (!tourActive || tourCameras.isEmpty()) return

        val camera = tourCameras[tourCurrentIndex]
        val serverUrl = webServer?.getSavedGo2rtcUrl() ?: currentGo2rtcUrl

        if (serverUrl.isEmpty()) {
            Log.e(tag, "Tour error: No go2rtc server URL configured")
            stopTour()
            return
        }

        Log.d(tag, "Tour: Playing camera ${tourCurrentIndex + 1}/${tourCameras.size}: ${camera.name}")

        playStream(serverUrl, camera.streamName, camera.protocol)

        tourCurrentIndex = (tourCurrentIndex + 1) % tourCameras.size

        tourHandler.postDelayed({
            playNextTourCamera()
        }, (tourDuration * 1000).toLong())
    }

    private fun stopTour() {
        if (!tourActive) return

        tourActive = false
        tourHandler.removeCallbacksAndMessages(null)
        Toast.makeText(this, "Tour stopped", Toast.LENGTH_SHORT).show()
        Log.d(tag, "Tour stopped")
    }

    @SuppressLint("SetTextI18n")
    private fun playStream(
        go2rtcUrl: String,
        streamName: String,
        protocol: String = "mse",
        forceFullReload: Boolean = false
    ) {
        try {
            Log.d(tag, "=== Play Stream Request ===")
            Log.d(tag, "go2rtc URL: $go2rtcUrl")
            Log.d(tag, "Stream Name: $streamName")
            Log.d(tag, "Protocol: $protocol")

            var normalizedUrl = go2rtcUrl.trim().trimEnd('/')

            if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
                normalizedUrl = "http://$normalizedUrl"
                Log.d(tag, "Added http:// prefix")
            }

            val wsProtocol = if (normalizedUrl.startsWith("https://")) "wss://" else "ws://"
            val serverUrl = normalizedUrl
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')

            val streamUrl = "$wsProtocol$serverUrl/api/ws?src=$streamName"

            Log.d(tag, "Normalized URL: $normalizedUrl")
            Log.d(tag, "WebSocket URL: $streamUrl")

            statusOverlay.visibility = View.VISIBLE
            errorTextView.visibility = View.GONE
            ipTextView.visibility = View.GONE

            val isFirstLoad = currentStreamName.isEmpty()
            val sameProtocol = protocol == currentProtocol
            val canAttemptInPlaceSwitch =
                !forceFullReload && !isFirstLoad && sameProtocol && !pendingPlayRequest

            stopStreamHealthMonitoring()

            if (canAttemptInPlaceSwitch) {
                val escapedUrl = streamUrl
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                val escapedName = streamName
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")

                Log.d(tag, "Dispatching in-place stream switch")

                webView.evaluateJavascript(
                    "(function(){ if (typeof switchStream === 'function') { return switchStream('$escapedUrl', '$escapedName'); } return null; })();"
                ) { result ->
                    if (result == "true") {
                        Log.d(tag, "In-place stream switch succeeded")
                        currentGo2rtcUrl = go2rtcUrl
                        currentStreamName = streamName
                        currentProtocol = protocol
                        webServer?.updatePlaybackState(streamName, protocol, go2rtcUrl)
                        lastStreamActivity = System.currentTimeMillis()
                        statusOverlay.visibility = View.GONE
                        errorTextView.visibility = View.GONE
                        startStreamHealthMonitoring()
                    } else {
                        Log.w(tag, "switchStream unavailable, falling back to full reload")
                        loadStreamFullReload(
                            go2rtcUrl,
                            streamName,
                            protocol,
                            normalizedUrl,
                            streamUrl
                        )
                    }
                }
            } else {
                loadStreamFullReload(
                    go2rtcUrl,
                    streamName,
                    protocol,
                    normalizedUrl,
                    streamUrl
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Error playing stream", e)
            webServer?.addLog("Play error: ${e.message}")
            Toast.makeText(this, "Play error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadStreamFullReload(
        go2rtcUrl: String,
        streamName: String,
        protocol: String,
        normalizedUrl: String,
        streamUrl: String
    ) {
        pendingPlayRequest = true
        cleanupWebView()

        Handler(Looper.getMainLooper()).postDelayed({
            currentGo2rtcUrl = go2rtcUrl
            currentStreamName = streamName
            currentProtocol = protocol
            webServer?.updatePlaybackState(streamName, protocol, go2rtcUrl)

            val html = webServer?.getStreamHtml(streamUrl, streamName, protocol) ?: ""

            if (html.isBlank()) {
                Log.e(tag, "Generated stream HTML was blank")
                webServer?.addLog("Generated stream HTML was blank")
                errorTextView.text = "Failed to generate stream page"
                errorTextView.visibility = View.VISIBLE
                pendingPlayRequest = false
                return@postDelayed
            }

            Log.d(tag, "HTML length: ${html.length} characters")

            try {
                val baseUrl = "$normalizedUrl/"
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
                Log.d(tag, "Stream HTML loaded into WebView (full reload)")

                lastStreamActivity = System.currentTimeMillis()
                statusOverlay.visibility = View.GONE
                errorTextView.visibility = View.GONE
                startStreamHealthMonitoring()
            } catch (e: Exception) {
                Log.e(tag, "Error loading HTML into WebView", e)
                webServer?.addLog("WebView load error: ${e.message}")
                errorTextView.text = "WebView load error: ${e.message}"
                errorTextView.visibility = View.VISIBLE
            } finally {
                pendingPlayRequest = false
            }
        }, 300)
    }

    private fun getLogoBase64(): String {
        return try {
            assets.open("logo.png").use { inputStream ->
                val bytes = inputStream.readBytes()
                "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error loading logo.png from assets", e)
            ""
        }
    }

    private fun showPlaceholder() {
        val ipAddress = getLocalIpAddress()
        val logoSrc = getLogoBase64()

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        background-color: black;
                        color: #aaa;
                        font-family: sans-serif;
                        display: flex;
                        flex-direction: column;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        margin: 0;
                        text-align: center;
                    }
                    h1 {
                        color: #fff;
                        font-weight: normal;
                        margin-bottom: 5px;
                    }
                    .url {
                        font-size: 1.5em;
                        color: #4fc3f7;
                        margin-top: 15px;
                    }
                    .info {
                        font-size: 0.9em;
                        margin-top: 30px;
                        opacity: 0.6;
                    }
                    .logo {
                        max-width: 250px;
                        height: auto;
                    }
                </style>
            </head>
            <body>
                ${if (logoSrc.isNotEmpty()) "<img src=\"$logoSrc\" alt=\"TPN Stream Viewer\" class=\"logo\">" else ""}
                <p>Awaiting Stream</p>
                <p class="info">Configure via web interface:</p>
                <div class="url">http://$ipAddress:$currentPort</div>
            </body>
            </html>
        """.trimIndent()

        try {
            Log.d(tag, "Loading placeholder HTML (${html.length} bytes)")
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            Log.d(tag, "Placeholder loaded successfully")
            ipTextView.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(tag, "Error showing placeholder", e)
            try {
                webView.loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
            } catch (e2: Exception) {
                Log.e(tag, "Can't load fallback HTML", e2)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun tryAlternativePort() {
        Thread {
            val alternativePorts = listOf(9999, 8000, 5000, 3000, 7777)

            for (port in alternativePorts) {
                try {
                    Log.d(tag, "Trying alternative port $port")
                    webServer?.stop()
                    Thread.sleep(100)

                    val newServer = AndroidWebServer(
                        port,
                        applicationContext,
                        onStreamConfig = { go2rtcUrl, streamName, protocol ->
                            runOnUiThread {
                                try {
                                    playStream(go2rtcUrl, streamName, protocol)
                                } catch (e: Exception) {
                                    Log.e(tag, "Error in callback", e)
                                }
                            }
                        },
                        onTourStart = { cameras, duration ->
                            runOnUiThread {
                                try {
                                    startTour(cameras, duration)
                                } catch (e: Exception) {
                                    Log.e(tag, "Error starting tour", e)
                                }
                            }
                        },
                        onTourStop = {
                            runOnUiThread {
                                try {
                                    stopTour()
                                } catch (e: Exception) {
                                    Log.e(tag, "Error stopping tour", e)
                                }
                            }
                        },
                        getCameras = { loadCameras() },
                        saveCameras = { cameras -> saveCameras(cameras) }
                    )

                    newServer.setBurnInProtectionCallback { enabled ->
                        runOnUiThread {
                            setBurnInProtection(enabled)
                        }
                    }

                    newServer.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    webServer = newServer
                    currentPort = port
                    val ipAddress = getLocalIpAddress()

                    Log.d(tag, "Server started on alternative port $port")

                    runOnUiThread {
                        ipTextView.text = "http://$ipAddress:$port"
                        if (currentStreamName.isEmpty()) {
                            showPlaceholder()
                        }
                        Toast.makeText(
                            this@MainActivity,
                            "Server: http://$ipAddress:$port",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@Thread
                } catch (e: Exception) {
                    Log.e(tag, "Port $port failed: ${e.message}")
                }
            }

            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Could not start server on any port",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        try {
            webView.onPause()
            webView.pauseTimers()
        } catch (e: Exception) {
            Log.e(tag, "Error in onPause", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView.onResume()
            webView.resumeTimers()
        } catch (e: Exception) {
            Log.e(tag, "Error in onResume", e)
        }
    }

    override fun onDestroy() {
        try {
            webServer?.stop()
            webServer = null
            tourHandler.removeCallbacksAndMessages(null)
            burnInHandler.removeCallbacksAndMessages(null)
            stopStreamHealthMonitoring()

            webView.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in onDestroy", e)
        } finally {
            super.onDestroy()
        }
    }
}
