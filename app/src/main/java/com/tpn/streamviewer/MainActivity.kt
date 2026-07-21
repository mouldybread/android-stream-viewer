package com.tpn.streamviewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var webServer: AndroidWebServer? = null

    private val webServerPort = 9090
    private val tag = "StreamViewer"

    private var currentGo2rtcUrl = ""
    private var currentStreamName = ""
    private var currentProtocol = "auto"

    private var playerShellLoaded = false
    private var pendingSwitch: Triple<String, String, String>? = null

    private var tourActive = false
    private var tourCameras = listOf<CameraConfig>()
    private var tourDuration = 60
    private var tourCurrentIndex = 0
    private val tourHandler = Handler(Looper.getMainLooper())

    private var burnInProtectionEnabled = true
    private val burnInHandler = Handler(Looper.getMainLooper())
    private var burnInBlankActive = false
    private val burnInIntervalMs = 2 * 60 * 60 * 1000L
    private val burnInDurationMs = 60 * 1000L

    private val streamHealthHandler = Handler(Looper.getMainLooper())
    private var lastStreamActivity = System.currentTimeMillis()
    private val streamTimeoutMs = 30 * 1000L
    private var streamHealthCheckActive = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(tag, "onCreate started - Build: ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hideSystemUI()

            setContentView(R.layout.activity_main)

            webView = findViewById(R.id.webView)
            webView.setBackgroundColor(Color.BLACK)

            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val isFirstRun = !prefs.getBoolean("initialized", false)

            if (isFirstRun) {
                Log.d(tag, "First run detected - initializing WebView")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        WebView.setDataDirectorySuffix("stream_viewer")
                        Log.d(tag, "WebView data directory suffix set")
                    } catch (e: Exception) {
                        Log.e(tag, "Error setting WebView data directory", e)
                    }
                }
            }

            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportMultipleWindows(false)
                }

                webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                WebView.setWebContentsDebuggingEnabled(true)

                Log.d(tag, "WebView settings configured")
            } catch (e: Exception) {
                Log.e(tag, "Error configuring WebView", e)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(tag, "Page loaded: $url")

                    pendingSwitch?.let { (go2rtcUrl, streamName, protocol) ->
                        pendingSwitch = null
                        dispatchSwitchToWebView(go2rtcUrl, streamName, protocol)
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    Log.e(tag, "WebView error: $description (code: $errorCode) at $failingUrl")
                    webServer?.addLog("WebView error: $description")
                    playerShellLoaded = false

                    if (currentStreamName.isNotEmpty() && currentGo2rtcUrl.isNotEmpty()) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            Log.d(tag, "Attempting stream recovery after error...")
                            playStream(currentGo2rtcUrl, currentStreamName, currentProtocol)
                        }, 5000)
                    }
                }
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
                        checkStreamHealth()
                    }
                }
            }, "AndroidInterface")

            startWebServer()

            val delay = if (isFirstRun) 2000L else 1000L

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Log.d(tag, "Loading placeholder screen...")
                    showPlaceholder()

                    if (isFirstRun) {
                        prefs.edit { putBoolean("initialized", true) }
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
                    val serverUrl = webServer?.go2rtcServerUrl ?: ""

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
                if (timeSinceActivity > streamTimeoutMs && currentStreamName.isNotEmpty()) {
                    Log.w(tag, "Stream appears frozen (${timeSinceActivity}ms since activity)")
                    webServer?.addLog("Stream timeout detected - attempting recovery")

                    playStream(currentGo2rtcUrl, currentStreamName, currentProtocol)
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
        if (timeSinceActivity > streamTimeoutMs) {
            Log.w(tag, "Stream health check failed")
            webServer?.addLog("Stream health check failed - reloading")
            playStream(currentGo2rtcUrl, currentStreamName, currentProtocol)
        }
    }

    private fun startBurnInProtection() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        burnInProtectionEnabled = prefs.getBoolean("burn_in_protection", true)

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
        }, burnInIntervalMs)
    }

    private fun triggerBurnInBlank() {
        if (burnInBlankActive) return

        burnInBlankActive = true
        Log.d(tag, "Burn-in protection: Blanking screen for 1 minute")
        webServer?.addLog("Burn-in protection: Screen blanked")

        runOnUiThread {
            playerShellLoaded = false
            webView.loadData("<html><body style='background:#000;margin:0'></body></html>", "text/html", "UTF-8")
        }

        burnInHandler.postDelayed({
            burnInBlankActive = false
            Log.d(tag, "Burn-in protection: Restoring display")
            webServer?.addLog("Burn-in protection: Display restored")

            runOnUiThread {
                if (currentStreamName.isNotEmpty() && currentGo2rtcUrl.isNotEmpty()) {
                    playStream(currentGo2rtcUrl, currentStreamName, currentProtocol)
                } else {
                    showPlaceholder()
                }
            }
        }, burnInDurationMs)
    }

    private fun setBurnInProtection(enabled: Boolean) {
        burnInProtectionEnabled = enabled
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit { putBoolean("burn_in_protection", enabled) }

        Log.d(tag, "Burn-in protection ${if (enabled) "enabled" else "disabled"}")
        webServer?.addLog("Burn-in protection ${if (enabled) "enabled" else "disabled"}")

        if (enabled) {
            startBurnInProtection()
        } else {
            burnInHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun startWebServer() {
        try {
            Log.d(tag, "Attempting to start server on port $webServerPort")

            webServer = AndroidWebServer(
                webServerPort,
                this,
                onStreamConfig = { go2rtcUrl, streamName, protocol ->
                    runOnUiThread {
                        try {
                            playStream(go2rtcUrl, streamName, protocol)
                        } catch (e: Exception) {
                            Log.e(tag, "Error in stream config callback", e)
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
                getCameras = {
                    loadCameras()
                },
                saveCameras = { cameras ->
                    saveCameras(cameras)
                }
            )

            webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

            webServer?.setBurnInProtectionCallback { enabled ->
                runOnUiThread {
                    setBurnInProtection(enabled)
                }
            }

            val ipAddress = getLocalIpAddress()
            Log.d(tag, "Web server started successfully on port $webServerPort")
            Log.d(tag, "Access at: http://$ipAddress:$webServerPort")

            Toast.makeText(
                this,
                "Server: http://$ipAddress:$webServerPort",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: BindException) {
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
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "unknown"
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
            val prefs = getSharedPreferences("cameras", MODE_PRIVATE)
            val json = prefs.getString("camera_list", "[]") ?: "[]"
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
            val prefs = getSharedPreferences("cameras", MODE_PRIVATE)
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

            prefs.edit { putString("camera_list", jsonArray.toString()) }
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
        val serverUrl = webServer?.go2rtcServerUrl ?: ""

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

    private fun playStream(go2rtcUrl: String, streamName: String, protocol: String = "mse") {
        try {
            Log.d(tag, "=== Play Stream Request ===")
            Log.d(tag, "go2rtc URL: $go2rtcUrl")
            Log.d(tag, "Stream Name: $streamName")
            Log.d(tag, "Protocol: $protocol")

            stopStreamHealthMonitoring()

            currentGo2rtcUrl = go2rtcUrl
            currentStreamName = streamName
            currentProtocol = protocol

            if (playerShellLoaded) {
                dispatchSwitchToWebView(go2rtcUrl, streamName, protocol)
            } else {
                pendingSwitch = null
                loadPlayerShell(go2rtcUrl, streamName, protocol)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error playing stream", e)
            webServer?.addLog("Play error: ${e.message}")
            Toast.makeText(this, "Play error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPlayerShell(go2rtcUrl: String, streamName: String, protocol: String) {
        var normalizedUrl = go2rtcUrl.trim().trimEnd('/')

        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "http://$normalizedUrl"
            Log.d(tag, "Added http:// prefix")
        }

        val wsProtocol = if (normalizedUrl.startsWith("https://")) "wss://" else "ws://"
        val serverUrl = normalizedUrl
            .replace("http://", "")
            .replace("https://", "")
            .trimEnd('/')

        val streamUrl = "$wsProtocol$serverUrl/api/ws?src=$streamName"

        Log.d(tag, "Normalized URL: $normalizedUrl")
        Log.d(tag, "WebSocket URL: $streamUrl")

        val html = webServer?.getStreamHtml(streamUrl, streamName, protocol) ?: ""

        Log.d(tag, "HTML length: ${html.length} characters")

        playerShellLoaded = false
        webView.loadDataWithBaseURL(normalizedUrl, html, "text/html", "UTF-8", null)
        playerShellLoaded = true
        startStreamHealthMonitoring()
        Log.d(tag, "Stream HTML loaded into WebView (initial load)")
    }

    private fun dispatchSwitchToWebView(go2rtcUrl: String, streamName: String, protocol: String) {
        var normalizedUrl = go2rtcUrl.trim().trimEnd('/')

        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "http://$normalizedUrl"
        }

        val wsProtocol = if (normalizedUrl.startsWith("https://")) "wss://" else "ws://"
        val serverUrl = normalizedUrl
            .replace("http://", "")
            .replace("https://", "")
            .trimEnd('/')

        val streamUrl = "$wsProtocol$serverUrl/api/ws?src=$streamName"
        val forceWebRTC = protocol == "webrtc"
        val forceMSE = protocol == "mse"

        val safeStreamUrl = streamUrl.replace("\\", "\\\\").replace("'", "\\'")
        val safeStreamName = streamName.replace("\\", "\\\\").replace("'", "\\'")

        val js = "if (typeof switchStream === 'function') { " +
                "switchStream('$safeStreamUrl', '$safeStreamName', $forceWebRTC, $forceMSE); true; " +
                "} else { false; }"

        Log.d(tag, "Dispatching in-place stream switch: $streamUrl")

        webView.evaluateJavascript(js) { result ->
            if (result != "true") {
                Log.w(tag, "switchStream() not available in page, falling back to full reload")
                runOnUiThread {
                    loadPlayerShell(go2rtcUrl, streamName, protocol)
                }
            } else {
                startStreamHealthMonitoring()
            }
        }
    }

    private fun showPlaceholder() {
        try {
            Log.d(tag, "showPlaceholder() called")

            val logoBase64 = try {
                val inputStream = assets.open("logo.png")
                val bytes = inputStream.readBytes()
                inputStream.close()
                Log.d(tag, "Logo loaded: ${bytes.size} bytes")
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(tag, "Error loading logo", e)
                null
            }

            val logoHtml = if (logoBase64 != null) {
                "<img src=\"data:image/png;base64,$logoBase64\" style=\"max-width:200px;\" />"
            } else {
                "<div style=\"font-size:64px;\">\uD83D\uDCF9</div>"
            }

            val ipAddress = getLocalIpAddress()

            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                <style>
                body { background:#000; color:#fff; margin:0; height:100vh; display:flex; flex-direction:column; align-items:center; justify-content:center; font-family:Arial, sans-serif; }
                </style>
                </head>
                <body>
                $logoHtml
                <h2>Awaiting Stream</h2>
                <p>Configure via web interface:</p>
                <p>http://$ipAddress:$webServerPort</p>
                </body>
                </html>
            """.trimIndent()

            Log.d(tag, "Loading placeholder HTML (${html.length} bytes)")
            playerShellLoaded = false
            webView.loadDataWithBaseURL("http://localhost", html, "text/html", "UTF-8", null)
            Log.d(tag, "Placeholder loaded successfully")

        } catch (e: Exception) {
            Log.e(tag, "Error showing placeholder", e)
            try {
                webView.loadData("", "text/html", "UTF-8")
            } catch (e2: Exception) {
                Log.e(tag, "Can't load fallback HTML", e2)
            }
        }
    }
    private fun tryAlternativePort() {
        val alternativePorts = listOf(9999, 8000, 5000, 3000, 7777)

        for (port in alternativePorts) {
            try {
                Log.d(tag, "Trying alternative port $port")

                webServer?.stop()
                Thread.sleep(100)

                webServer = AndroidWebServer(
                    port,
                    this,
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
                    getCameras = {
                        loadCameras()
                    },
                    saveCameras = { cameras ->
                        saveCameras(cameras)
                    }
                )

                webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                val ipAddress = getLocalIpAddress()
                Log.d(tag, "Server started on alternative port $port")
                Toast.makeText(
                    this,
                    "Server: http://$ipAddress:$port",
                    Toast.LENGTH_LONG
                ).show()
                return

            } catch (e: Exception) {
                Log.e(tag, "Port $port failed: ${e.message}")
            }
        }

        Toast.makeText(this, "Could not start server on any port", Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            webServer?.stop()
            tourHandler.removeCallbacksAndMessages(null)
            burnInHandler.removeCallbacksAndMessages(null)
            stopStreamHealthMonitoring()
        } catch (e: Exception) {
            Log.e(tag, "Error in onDestroy", e)
        }
    }
}