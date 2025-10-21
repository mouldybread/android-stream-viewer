package com.tpn.streamviewer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var webServer: AndroidWebServer? = null

    private val webServerPort = 9090
    private val tag = "StreamViewer"

    private var currentGo2rtcUrl = ""
    private var currentStreamName = ""
    private var currentProtocol = "auto"

    private var tourActive = false
    private var tourCameras = listOf<CameraConfig>()
    private var tourDuration = 60
    private var tourCurrentIndex = 0
    private val tourHandler = Handler(Looper.getMainLooper())

    // Burn-in protection
    private var burnInProtectionEnabled = true
    private val burnInHandler = Handler(Looper.getMainLooper())
    private var burnInBlankActive = false
    private val BURN_IN_INTERVAL = 2 * 60 * 60 * 1000L  // 2 hours in milliseconds
    private val BURN_IN_DURATION = 60 * 1000L  // 1 minute in milliseconds

    // Stream health monitoring
    private val streamHealthHandler = Handler(Looper.getMainLooper())
    private var lastStreamActivity = System.currentTimeMillis()
    private val STREAM_TIMEOUT = 30 * 1000L
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

            // Set background to black immediately
            webView.setBackgroundColor(android.graphics.Color.BLACK)

            // Check if this is first run
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val isFirstRun = !prefs.getBoolean("initialized", false)

            if (isFirstRun) {
                Log.d(tag, "First run detected - initializing WebView")
                // Pre-initialize WebView on first run (API 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        WebView.setDataDirectorySuffix("streamviewer")
                        Log.d(tag, "WebView data directory suffix set")
                    } catch (e: Exception) {
                        Log.e(tag, "Error setting WebView data directory", e)
                    }
                }
            }

            // Configure WebView settings
            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportMultipleWindows(false)
                }

                webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

                // Enable WebView debugging
                WebView.setWebContentsDebuggingEnabled(true)

                Log.d(tag, "WebView settings configured")
            } catch (e: Exception) {
                Log.e(tag, "Error configuring WebView", e)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(tag, "Page loaded: $url")
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


            // JavaScript interface to monitor stream health
            webView.addJavascriptInterface(object {
                @Suppress("unused")
                @android.webkit.JavascriptInterface
                fun onStreamPlaying() {
                    runOnUiThread {
                        lastStreamActivity = System.currentTimeMillis()
                        Log.d(tag, "Stream heartbeat received")
                    }
                }

                @Suppress("unused")
                @android.webkit.JavascriptInterface
                fun onStreamError(message: String) {
                    runOnUiThread {
                        Log.e(tag, "Stream error from JS: $message")
                        webServer?.addLog("Stream error: $message")
                        checkStreamHealth()
                    }
                }
            }, "AndroidInterface")

            // Start web server first
            startWebServer()

            // Longer delay on first run for WebView initialization
            val delay = if (isFirstRun) 2000L else 1000L

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Log.d(tag, "Loading placeholder screen...")
                    showPlaceholder()

                    // Mark as initialized after successful first load
                    if (isFirstRun) {
                        prefs.edit { putBoolean("initialized", true) }
                        Log.d(tag, "First run initialization complete")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error showing placeholder", e)
                }
            }, delay)

            // Start burn-in protection after everything is initialized
            Handler(Looper.getMainLooper()).postDelayed({
                startBurnInProtection()
            }, 5000)

        } catch (e: Exception) {
            Log.e(tag, "CRITICAL ERROR in onCreate", e)
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
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
                if (timeSinceActivity > STREAM_TIMEOUT && currentStreamName.isNotEmpty()) {
                    Log.w(tag, "Stream appears frozen (${timeSinceActivity}ms since activity)")
                    webServer?.addLog("Stream timeout detected - attempting recovery")

                    cleanupWebView()
                    Handler(Looper.getMainLooper()).postDelayed({
                        playStream(currentGo2rtcUrl, currentStreamName, currentProtocol)
                    }, 1000)
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
        if (timeSinceActivity > STREAM_TIMEOUT) {
            Log.w(tag, "Stream health check failed")
            webServer?.addLog("Stream health check failed - reloading")
            cleanupWebView()
            Handler(Looper.getMainLooper()).postDelayed({
                playStream(currentGo2rtcUrl, currentStreamName, currentProtocol)
            }, 1000)
        }
    }

    private fun cleanupWebView() {
        try {
            runOnUiThread {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearCache(true)
                webView.clearHistory()
                Log.d(tag, "WebView cleaned up")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error cleaning up WebView", e)
        }
    }

    private fun startBurnInProtection() {
        // Load burn-in setting
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
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
            // Schedule next check
            scheduleBurnInProtection()
        }, BURN_IN_INTERVAL)
    }

    private fun triggerBurnInBlank() {
        if (burnInBlankActive) return

        burnInBlankActive = true
        Log.d(tag, "Burn-in protection: Blanking screen for 1 minute")
        webServer?.addLog("Burn-in protection: Screen blanked")

        // Show black screen
        runOnUiThread {
            webView.loadData("<html><body style='background:#000'></body></html>", "text/html", "UTF-8")
        }

        // Restore after 1 minute
        burnInHandler.postDelayed({
            burnInBlankActive = false
            Log.d(tag, "Burn-in protection: Restoring display")
            webServer?.addLog("Burn-in protection: Display restored")

            runOnUiThread {
                // Restore previous content
                if (currentStreamName.isNotEmpty() && currentGo2rtcUrl.isNotEmpty()) {
                    playStream(currentGo2rtcUrl, currentStreamName, currentProtocol)
                } else {
                    showPlaceholder()
                }
            }
        }, BURN_IN_DURATION)
    }

    private fun setBurnInProtection(enabled: Boolean) {
        burnInProtectionEnabled = enabled
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
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

            // Set burn-in protection callback
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
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
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
            val prefs = getSharedPreferences("cameras", Context.MODE_PRIVATE)
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
            val prefs = getSharedPreferences("cameras", Context.MODE_PRIVATE)
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
            cleanupWebView()

            currentGo2rtcUrl = go2rtcUrl
            currentStreamName = streamName
            currentProtocol = protocol

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

            if (html.contains("stun.l.google")) {
                Log.e(tag, "⚠️ WARNING: HTML still contains STUN server!")
                webServer?.addLog("ERROR: STUN server still in HTML")
            } else {
                Log.d(tag, "✓ Verified: No STUN server in HTML")
            }

            Log.d(tag, "HTML length: ${html.length} characters")

            webView.loadDataWithBaseURL(normalizedUrl, html, "text/html", "UTF-8", null)

            Log.d(tag, "Stream HTML loaded into WebView")

        } catch (e: Exception) {
            Log.e(tag, "Error playing stream", e)
            webServer?.addLog("Play error: ${e.message}")
            Toast.makeText(this, "Play error: ${e.message}", Toast.LENGTH_SHORT).show()
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
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(tag, "Error loading logo", e)
                null
            }

            val logoHtml = if (logoBase64 != null) {
                """<img src="data:image/png;base64,$logoBase64" alt="Logo" class="logo">"""
            } else {
                """<div class="logo-fallback">📹</div>"""
            }

            val ipAddress = getLocalIpAddress()

            val html = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        body {
            margin: 0;
            padding: 0;
            background: #000;
            display: flex;
            justify-content: center;
            align-items: center;
            height: 100vh;
            font-family: Arial, sans-serif;
            color: #666;
        }
        .placeholder {
            text-align: center;
        }
.logo {
    width: 150px;
    height: 150px;
    margin: 0 auto 30px;
    border-radius: 20px;
    object-fit: contain;
    background: radial-gradient(circle, #1a1a1a 0%, #000 70%);
    padding: 25px;
}
        .logo-fallback {
            font-size: 120px;
            margin-bottom: 20px;
        }
        .message {
            font-size: 24px;
            margin-bottom: 10px;
            color: #888;
        }
        .submessage {
            font-size: 16px;
            color: #555;
            margin-top: 20px;
        }
        .url {
            font-size: 18px;
            color: #007bff;
            margin-top: 10px;
            font-family: monospace;
        }
    </style>
</head>
<body>
    <div class="placeholder">
        $logoHtml
        <div class="message">Awaiting Stream</div>
        <div class="submessage">Configure via web interface:</div>
        <div class="url">http://$ipAddress:$webServerPort</div>
    </div>
</body>
</html>
        """.trimIndent()

            Log.d(tag, "Loading placeholder HTML (${html.length} bytes)")
            webView.loadDataWithBaseURL("http://localhost", html, "text/html", "UTF-8", null)
            Log.d(tag, "Placeholder loaded successfully")

        } catch (e: Exception) {
            Log.e(tag, "Error showing placeholder", e)
            try {
                webView.loadData("<html><body style='background:#000'></body></html>", "text/html", "UTF-8")
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
            // API 30+ (Android 11+)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // API 29 and below
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
        super.onDestroy()
        try {
            webServer?.stop()
            tourHandler.removeCallbacksAndMessages(null)
            burnInHandler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(tag, "Error in onDestroy", e)
        }
    }
}
