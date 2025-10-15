package com.tpn.streamviewer

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CameraConfig(
    val id: String,
    val name: String,
    val streamName: String,
    var enabled: Boolean,
    var protocol: String,
    var order: Int
)

class AndroidWebServer(
    port: Int,
    private val context: Context,
    private val onStreamConfig: (String, String, String) -> Unit,
    private val onTourStart: (List<CameraConfig>, Int) -> Unit,
    private val onTourStop: () -> Unit,
    private val getCameras: () -> List<CameraConfig>,
    private val saveCameras: (List<CameraConfig>) -> Unit
) : NanoHTTPD(port) {

    private val TAG = "AndroidWebServer"
    private val logs = mutableListOf<String>()
    private val maxLogs = 500

    var go2rtcServerUrl: String = ""
        private set

    // Track current state
    private var currentStreamName: String? = null
    private var currentProtocol: String? = null
    private var tourRunning: Boolean = false
    private var defaultStreamName: String? = null

    // Burn-in protection callback
    private var burnInProtectionCallback: ((Boolean) -> Unit)? = null

    init {
        Log.d(TAG, "Server initialized on port $port")
        addLog("Server initialized on port $port")
    }

    fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message"
        synchronized(logs) {
            logs.add(logEntry)
            if (logs.size > maxLogs) {
                logs.removeAt(0)
            }
        }
        Log.d(TAG, message)
    }

    fun setBurnInProtectionCallback(callback: (Boolean) -> Unit) {
        burnInProtectionCallback = callback
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        addLog("${method.name} $uri")

        return when {
            uri == "/api/cameras" && method == Method.GET -> handleGetCameras()
            uri == "/api/camera-names" && method == Method.GET -> handleGetCameraNames()
            uri == "/api/cameras" && method == Method.POST -> handleSaveCameras(session)
            uri == "/api/config" && method == Method.POST -> handleStreamConfig(session)
            uri == "/api/discover" && method == Method.POST -> handleDiscoverCameras(session)
            uri == "/api/tour/start" && method == Method.POST -> handleTourStart(session)
            uri == "/api/tour/stop" && method == Method.POST -> handleTourStop()
            uri == "/api/logs" && method == Method.GET -> handleGetLogs()

            // NEW ENDPOINTS
            uri == "/api/status" && method == Method.GET -> handleGetStatus()
            uri.startsWith("/api/camera/") && uri.endsWith("/toggle") && method == Method.POST -> handleToggleCamera(session, uri)
            uri == "/api/tour/status" && method == Method.GET -> handleGetTourStatus()
            uri == "/api/default" && method == Method.POST -> handleSetDefault(session)
            uri == "/api/default" && method == Method.GET -> handleGetDefault()
            uri == "/api/burn-in/status" && method == Method.GET -> handleGetBurnInStatus()
            uri == "/api/burn-in/toggle" && method == Method.POST -> handleToggleBurnIn(session)

            uri == "/" -> serveFile("index.html")
            uri.startsWith("/") -> serveFile(uri.substring(1))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    // Get current playback status
    private fun handleGetStatus(): Response {
        val status = JSONObject().apply {
            put("playing", currentStreamName != null)
            put("streamName", currentStreamName ?: JSONObject.NULL)
            put("protocol", currentProtocol ?: JSONObject.NULL)
            put("tourActive", tourRunning)
            put("go2rtcUrl", go2rtcServerUrl)
            put("defaultStream", defaultStreamName ?: JSONObject.NULL)
        }
        addLog("Status requested: ${if (currentStreamName != null) "Playing $currentStreamName" else "Idle"}")
        return newFixedLengthResponse(Response.Status.OK, "application/json", status.toString())
    }

    // Toggle camera enabled/disabled
    private fun handleToggleCamera(session: IHTTPSession, uri: String): Response {
        return try {
            val cameraId = uri.split("/")[3]

            val cameras = getCameras().toMutableList()
            val camera = cameras.find { it.id == cameraId }

            if (camera == null) {
                addLog("Toggle failed: Camera $cameraId not found")
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    JSONObject().put("error", "Camera not found").toString()
                )
            }

            camera.enabled = !camera.enabled
            saveCameras(cameras)

            addLog("Toggled camera ${camera.name}: ${if (camera.enabled) "enabled" else "disabled"}")

            val response = JSONObject().apply {
                put("success", true)
                put("cameraId", cameraId)
                put("cameraName", camera.name)
                put("enabled", camera.enabled)
            }

            newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling camera", e)
            addLog("Toggle error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    // Get tour status
    private fun handleGetTourStatus(): Response {
        val cameras = getCameras().filter { it.enabled }
        val status = JSONObject().apply {
            put("active", tourRunning)
            put("cameraCount", cameras.size)
            put("currentStream", currentStreamName ?: JSONObject.NULL)
        }
        addLog("Tour status: ${if (tourRunning) "active" else "inactive"}")
        return newFixedLengthResponse(Response.Status.OK, "application/json", status.toString())
    }

    // Set default stream
    private fun handleSetDefault(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val json = JSONObject(postData)

            defaultStreamName = json.optString("streamName", null)

            val prefs = context.getSharedPreferences("stream_settings", Context.MODE_PRIVATE)
            prefs.edit().putString("default_stream", defaultStreamName).apply()

            addLog("Default stream set: $defaultStreamName")

            val response = JSONObject().apply {
                put("success", true)
                put("defaultStream", defaultStreamName ?: JSONObject.NULL)
            }

            newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error setting default", e)
            addLog("Set default error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    // Get default stream
    private fun handleGetDefault(): Response {
        val prefs = context.getSharedPreferences("stream_settings", Context.MODE_PRIVATE)
        val defaultStream = prefs.getString("default_stream", null)

        val response = JSONObject().apply {
            put("defaultStream", defaultStream ?: JSONObject.NULL)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    // Get burn-in protection status
    private fun handleGetBurnInStatus(): Response {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("burn_in_protection", true)

        val status = JSONObject().apply {
            put("enabled", enabled)
            put("interval", 120) // 2 hours in minutes
            put("duration", 60)  // 1 minute in seconds
        }

        addLog("Burn-in protection status requested: ${if (enabled) "enabled" else "disabled"}")
        return newFixedLengthResponse(Response.Status.OK, "application/json", status.toString())
    }

    // Toggle burn-in protection
    private fun handleToggleBurnIn(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val json = JSONObject(postData)

            val enabled = json.getBoolean("enabled")

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("burn_in_protection", enabled).apply()

            burnInProtectionCallback?.invoke(enabled)

            addLog("Burn-in protection ${if (enabled) "enabled" else "disabled"}")

            val response = JSONObject().apply {
                put("success", true)
                put("enabled", enabled)
            }

            newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling burn-in protection", e)
            addLog("Burn-in toggle error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    private fun handleGetCameras(): Response {
        val cameras = getCameras()
        val jsonArray = JSONArray()

        cameras.forEach { cam ->
            jsonArray.put(JSONObject().apply {
                put("id", cam.id)
                put("name", cam.name)
                put("streamName", cam.streamName)
                put("enabled", cam.enabled)
                put("protocol", cam.protocol)
                put("order", cam.order)
            })
        }

        addLog("Cameras list requested: ${cameras.size} cameras")
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }

    private fun handleGetCameraNames(): Response {
        return try {
            val cameras = getCameras()
            val names = cameras.joinToString(",") { it.name }
            addLog("Camera names requested: $names")
            newFixedLengthResponse(Response.Status.OK, "text/plain", names)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera names", e)
            addLog("Camera names error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun handleSaveCameras(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val jsonArray = JSONArray(postData)

            val cameras = mutableListOf<CameraConfig>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                cameras.add(CameraConfig(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    streamName = obj.getString("streamName"),
                    enabled = obj.getBoolean("enabled"),
                    protocol = obj.optString("protocol", "mse"),
                    order = obj.optInt("order", i)
                ))
            }

            saveCameras(cameras)
            addLog("Saved ${cameras.size} cameras")

            newFixedLengthResponse(Response.Status.OK, "application/json",
                JSONObject().put("success", true).toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cameras", e)
            addLog("Save error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", e.message).toString())
        }
    }

    private fun handleStreamConfig(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val json = JSONObject(postData)

            go2rtcServerUrl = json.getString("go2rtcUrl")
            val streamName = json.getString("streamName")
            val protocol = json.optString("protocol", "mse")

            currentStreamName = streamName
            currentProtocol = protocol

            addLog("Playing: $streamName from $go2rtcServerUrl (protocol: $protocol)")
            onStreamConfig(go2rtcServerUrl, streamName, protocol)

            newFixedLengthResponse(Response.Status.OK, "application/json",
                JSONObject().put("success", true).toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring stream", e)
            addLog("Config error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", e.message).toString())
        }
    }

    private fun handleDiscoverCameras(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val json = JSONObject(postData)

            val serverUrl = json.getString("serverUrl").trimEnd('/')
            go2rtcServerUrl = serverUrl

            addLog("Discovering cameras from: $serverUrl")

            val url = URL("$serverUrl/api/streams")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode
            addLog("Discovery response code: $responseCode")

            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                addLog("Discovered streams from go2rtc")
                newFixedLengthResponse(Response.Status.OK, "application/json", response)
            } else {
                addLog("Discovery failed with code: $responseCode")
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    JSONObject().put("error", "Failed to discover cameras: HTTP $responseCode").toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering cameras", e)
            addLog("Discovery error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", e.message).toString())
        }
    }

    private fun handleTourStart(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val json = JSONObject(postData)

            val duration = json.getInt("duration")
            val cameras = getCameras().filter { it.enabled }

            if (cameras.isEmpty()) {
                addLog("Tour start failed: No enabled cameras")
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    JSONObject().put("error", "No enabled cameras").toString())
            }

            tourRunning = true
            addLog("Tour started: ${cameras.size} cameras, ${duration}s each")
            onTourStart(cameras, duration)

            newFixedLengthResponse(Response.Status.OK, "application/json",
                JSONObject().put("success", true).put("cameraCount", cameras.size).toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tour", e)
            addLog("Tour start error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", e.message).toString())
        }
    }

    private fun handleTourStop(): Response {
        return try {
            tourRunning = false
            currentStreamName = null
            currentProtocol = null
            addLog("Tour stopped")
            onTourStop()
            newFixedLengthResponse(Response.Status.OK, "application/json",
                JSONObject().put("success", true).toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tour", e)
            addLog("Tour stop error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", e.message).toString())
        }
    }

    private fun handleGetLogs(): Response {
        val logsText = synchronized(logs) {
            logs.joinToString("\n")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", logsText)
    }

    private fun serveFile(filename: String): Response {
        return try {
            val inputStream = context.assets.open(filename)
            val mimeType = when {
                filename.endsWith(".html") -> "text/html"
                filename.endsWith(".css") -> "text/css"
                filename.endsWith(".js") -> "application/javascript"
                filename.endsWith(".json") -> "application/json"
                filename.endsWith(".png") -> "image/png"
                filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg"
                else -> "application/octet-stream"
            }
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file: $filename", e)
            addLog("File serve error: $filename - ${e.message}")
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found: $filename")
        }
    }

    fun getStreamHtml(streamUrl: String, streamName: String, protocol: String): String {
        val forceWebRTC = protocol == "webrtc"
        val forceMSE = protocol == "mse"

        val safeStreamUrl = streamUrl.replace("\\", "\\\\").replace("'", "\\'")
        val safeStreamName = streamName.replace("\\", "\\\\").replace("'", "\\'")

        return try {
            val template = context.assets.open("stream.html").bufferedReader().readText()
            val html = template
                .replace("{{STREAM_URL}}", safeStreamUrl)
                .replace("{{STREAM_NAME}}", safeStreamName)
                .replace("{{FORCE_WEBRTC}}", forceWebRTC.toString())
                .replace("{{FORCE_MSE}}", forceMSE.toString())

            if (html.contains("stun.l.google")) {
                Log.e(TAG, "⚠️⚠️⚠️ CRITICAL: HTML STILL CONTAINS STUN SERVER! ⚠️⚠️⚠️")
                addLog("ERROR: STUN server found in HTML template!")
            } else {
                Log.d(TAG, "✓ STUN verification passed")
            }

            addLog("Stream HTML generated:")
            addLog("  URL: $safeStreamUrl")
            addLog("  Name: $safeStreamName")
            addLog("  Protocol: $protocol (WebRTC: $forceWebRTC, MSE: $forceMSE)")
            addLog("  HTML size: ${html.length} bytes")

            html
        } catch (e: Exception) {
            Log.e(TAG, "Error loading stream template", e)
            addLog("ERROR loading stream.html: ${e.message}")
            "<html><body>Error loading stream</body></html>"
        }
    }
}
