package com.tpn.streamviewer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CameraConfig(
    val id: String,
    val name: String,
    val streamName: String,
    var enabled: Boolean,
    var protocol: String,
    var order: Int
)

class AndroidWebServer(
    private val port: Int,
    private val context: Context,
    private val onStreamConfig: (String, String, String) -> Unit,
    private val onTourStart: (List<CameraConfig>, Int) -> Unit,
    private val onTourStop: () -> Unit,
    private val getCameras: () -> List<CameraConfig>,
    private val saveCameras: (List<CameraConfig>) -> Unit
) : NanoHTTPD(port) {

    private val tag = "AndroidWebServer"
    private val logs = ArrayDeque<String>()
    private val maxLogs = 500

    private val appPrefs: SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val streamPrefs: SharedPreferences =
        context.getSharedPreferences("stream_settings", Context.MODE_PRIVATE)

    @Volatile
    private var go2rtcServerUrl: String = ""

    @Volatile
    private var currentStreamName: String? = null

    @Volatile
    private var currentProtocol: String? = null

    @Volatile
    private var tourRunning: Boolean = false

    @Volatile
    private var defaultStreamName: String? = null

    private var burnInProtectionCallback: ((Boolean) -> Unit)? = null

    init {
        Log.d(tag, "Server initialized on port $port")
        addLog("Server initialized on port $port")

        go2rtcServerUrl = synchronized(appPrefs) {
            appPrefs.getString("go2rtc_server_url", "") ?: ""
        }

        if (go2rtcServerUrl.isNotEmpty()) {
            Log.d(tag, "Loaded saved go2rtc URL: $go2rtcServerUrl")
            addLog("Loaded go2rtc URL: $go2rtcServerUrl")
        }

        defaultStreamName = synchronized(streamPrefs) {
            streamPrefs.getString("default_stream", null)
        }
    }

    fun getSavedGo2rtcUrl(): String = go2rtcServerUrl

    fun updatePlaybackState(streamName: String?, protocol: String?, serverUrl: String? = null) {
        currentStreamName = streamName
        currentProtocol = protocol
        if (!serverUrl.isNullOrBlank()) {
            go2rtcServerUrl = serverUrl
        }
    }

    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val logEntry = "[$timestamp] $message"
        synchronized(logs) {
            logs.add(logEntry)
            if (logs.size > maxLogs) {
                logs.removeFirst()
            }
        }
        Log.d(tag, message)
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
            uri == "/api/save-server-url" && method == Method.POST -> handleSaveServerUrl(session)
            uri == "/api/scan-cameras" && method == Method.POST -> handleScanCameras()
            uri == "/api/status" && method == Method.GET -> handleGetStatus()
            uri.startsWith("/api/camera/") && uri.endsWith("/toggle") && method == Method.POST -> handleToggleCamera(uri)
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

    private fun extractPostData(session: IHTTPSession): String? {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"]
    }

    private fun handleSaveServerUrl(session: IHTTPSession): Response {
        return try {
            val postData = extractPostData(session)
            if (postData.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing request body").toString()
                )
            }

            val json = try {
                JSONObject(postData)
            } catch (je: JSONException) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Invalid JSON: ${je.message}").toString()
                )
            }

            if (!json.has("url")) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing required field 'url'").toString()
                )
            }

            val url = json.getString("url").trim().trimEnd('/')
            go2rtcServerUrl = url

            synchronized(appPrefs) {
                appPrefs.edit { putString("go2rtc_server_url", url) }
            }

            addLog("go2rtc server URL saved: $url")
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject().put("success", true).toString()
            )
        } catch (e: Exception) {
            Log.e(tag, "Error saving server URL", e)
            addLog("Save server URL error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    private fun handleScanCameras(): Response {
        return try {
            val currentServerUrl = go2rtcServerUrl
            if (currentServerUrl.isEmpty()) {
                addLog("Scan failed: No go2rtc server configured")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("success", false).put("message", "No go2rtc server configured").toString()
                )
            }

            addLog("Scanning cameras from: $currentServerUrl")

            val streamsUrl = "$currentServerUrl/api/streams"
            val connection = (URL(streamsUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val streamsJson = JSONObject(response)

                val newCameras = mutableListOf<CameraConfig>()
                val existingCameras = getCameras()
                var order = existingCameras.size

                val keys = streamsJson.keys()
                while (keys.hasNext()) {
                    val streamName = keys.next()
                    if (existingCameras.none { it.streamName == streamName }) {
                        newCameras.add(
                            CameraConfig(
                                id = UUID.randomUUID().toString(),
                                name = streamName.uppercase(Locale.ROOT),
                                streamName = streamName,
                                enabled = true,
                                protocol = "mse",
                                order = order++
                            )
                        )
                    }
                }

                if (newCameras.isNotEmpty()) {
                    val allCameras = existingCameras + newCameras
                    saveCameras(allCameras)
                    addLog("Imported ${newCameras.size} new cameras")

                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        JSONObject()
                            .put("success", true)
                            .put("message", "Imported ${newCameras.size} cameras")
                            .put("count", newCameras.size)
                            .toString()
                    )
                } else {
                    addLog("No new cameras found")
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        JSONObject()
                            .put("success", true)
                            .put("message", "No new cameras found")
                            .put("count", 0)
                            .toString()
                    )
                }
            } else {
                addLog("Scan failed: HTTP $responseCode")
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    JSONObject()
                        .put("success", false)
                        .put("message", "Failed to connect to go2rtc server (HTTP $responseCode)")
                        .toString()
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Error scanning cameras", e)
            addLog("Scan error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("success", false).put("message", "Error: ${e.message}").toString()
            )
        }
    }

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

    private fun handleToggleCamera(uri: String): Response {
        return try {
            val parts = uri.split("/")
            if (parts.size < 4) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Malformed camera toggle URI").toString()
                )
            }
            val cameraId = parts[3]

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
            Log.e(tag, "Error toggling camera", e)
            addLog("Toggle error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

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

    private fun handleSetDefault(session: IHTTPSession): Response {
        return try {
            val postData = extractPostData(session)
            if (postData.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing request body").toString()
                )
            }

            val json = try {
                JSONObject(postData)
            } catch (je: JSONException) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Invalid JSON: ${je.message}").toString()
                )
            }

            defaultStreamName = if (json.has("streamName") && !json.isNull("streamName")) {
                json.getString("streamName")
            } else {
                null
            }

            synchronized(streamPrefs) {
                streamPrefs.edit { putString("default_stream", defaultStreamName) }
            }

            addLog("Default stream set: $defaultStreamName")

            val response = JSONObject().apply {
                put("success", true)
                put("defaultStream", defaultStreamName ?: JSONObject.NULL)
            }

            newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            Log.e(tag, "Error setting default", e)
            addLog("Set default error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    private fun handleGetDefault(): Response {
        val defaultStream = synchronized(streamPrefs) {
            streamPrefs.getString("default_stream", null)
        }

        val response = JSONObject().apply {
            put("defaultStream", defaultStream ?: JSONObject.NULL)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    private fun handleGetBurnInStatus(): Response {
        val enabled = synchronized(appPrefs) {
            appPrefs.getBoolean("burn_in_protection", true)
        }

        val status = JSONObject().apply {
            put("enabled", enabled)
            put("interval", 120)
            put("duration", 60)
        }

        addLog("Burn-in protection status requested: ${if (enabled) "enabled" else "disabled"}")
        return newFixedLengthResponse(Response.Status.OK, "application/json", status.toString())
    }

    private fun handleToggleBurnIn(session: IHTTPSession): Response {
        return try {
            val postData = extractPostData(session)
            if (postData.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing request body").toString()
                )
            }

            val json = try {
                JSONObject(postData)
            } catch (je: JSONException) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Invalid JSON: ${je.message}").toString()
                )
            }

            if (!json.has("enabled")) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing required field 'enabled'").toString()
                )
            }

            val enabled = json.getBoolean("enabled")

            synchronized(appPrefs) {
                appPrefs.edit { putBoolean("burn_in_protection", enabled) }
            }

            burnInProtectionCallback?.invoke(enabled)

            addLog("Burn-in protection ${if (enabled) "enabled" else "disabled"}")

            val response = JSONObject().apply {
                put("success", true)
                put("enabled", enabled)
            }

            newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            Log.e(tag, "Error toggling burn-in protection", e)
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
            Log.e(tag, "Error getting camera names", e)
            addLog("Camera names error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun handleSaveCameras(session: IHTTPSession): Response {
        return try {
            val postData = extractPostData(session)
            if (postData.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing request body").toString()
                )
            }

            val jsonArray = try {
                JSONArray(postData)
            } catch (je: JSONException) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Invalid JSON array: ${je.message}").toString()
                )
            }

            val cameras = mutableListOf<CameraConfig>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                cameras.add(
                    CameraConfig(
                        id = if (obj.has("id")) obj.getString("id") else UUID.randomUUID().toString(),
                        name = obj.getString("name"),
                        streamName = obj.getString("streamName"),
                        enabled = if (obj.has("enabled")) obj.getBoolean("enabled") else true,
                        protocol = obj.optString("protocol", "mse"),
                        order = obj.optInt("order", i)
                    )
                )
            }

            saveCameras(cameras)
            addLog("Saved ${cameras.size} cameras")

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject().put("success", true).toString()
            )
        } catch (e: Exception) {
            Log.e(tag, "Error saving cameras", e)
            addLog("Save error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    

    private fun handleStreamConfig(session: IHTTPSession): Response {
        return try {
            val postData = extractPostData(session)
            if (postData.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing request body").toString()
                )
            }

            val json = try {
                JSONObject(postData)
            } catch (je: JSONException) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Invalid JSON: ${je.message}").toString()
                )
            }

            if (!json.has("go2rtcUrl") || !json.has("streamName")) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing go2rtcUrl or streamName").toString()
                )
            }

            val go2rtcUrl = json.getString("go2rtcUrl")
            val streamName = json.getString("streamName")
            val protocol = json.optString("protocol", "mse")

            go2rtcServerUrl = go2rtcUrl
            currentStreamName = streamName
            currentProtocol = protocol

            addLog("Playing: $streamName from $go2rtcUrl (protocol: $protocol)")
            onStreamConfig(go2rtcUrl, streamName, protocol)

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject().put("success", true).toString()
            )
        } catch (e: Exception) {
            Log.e(tag, "Error configuring stream", e)
            addLog("Config error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    private fun handleDiscoverCameras(session: IHTTPSession): Response {
        return try {
            val postData = extractPostData(session)
            if (postData.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing request body").toString()
                )
            }

            val json = try {
                JSONObject(postData)
            } catch (je: JSONException) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Invalid JSON: ${je.message}").toString()
                )
            }

            if (!json.has("serverUrl")) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing serverUrl").toString()
                )
            }

            val serverUrl = json.getString("serverUrl").trim().trimEnd('/')
            go2rtcServerUrl = serverUrl

            synchronized(appPrefs) {
                appPrefs.edit { putString("go2rtc_server_url", serverUrl) }
            }

            addLog("Discovering cameras from: $serverUrl")

            val url = URL("$serverUrl/api/streams")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            addLog("Discovery response code: $responseCode")

            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                addLog("Discovered streams from go2rtc")
                newFixedLengthResponse(Response.Status.OK, "application/json", response)
            } else {
                addLog("Discovery failed with code: $responseCode")
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    JSONObject().put("error", "Failed to discover cameras: HTTP $responseCode").toString()
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Error discovering cameras", e)
            addLog("Discovery error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    private fun handleTourStart(session: IHTTPSession): Response {
        return try {
            val postData = extractPostData(session)
            if (postData.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Missing request body").toString()
                )
            }

            val json = try {
                JSONObject(postData)
            } catch (je: JSONException) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "Invalid JSON: ${je.message}").toString()
                )
            }

            val duration = json.optInt("duration", 10)
            val cameras = getCameras().filter { it.enabled }

            if (cameras.isEmpty()) {
                addLog("Tour start failed: No enabled cameras")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("error", "No enabled cameras").toString()
                )
            }

            tourRunning = true
            addLog("Tour started: ${cameras.size} cameras, ${duration}s each")
            onTourStart(cameras, duration)

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject().put("success", true).put("cameraCount", cameras.size).toString()
            )
        } catch (e: Exception) {
            Log.e(tag, "Error starting tour", e)
            addLog("Tour start error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    private fun handleTourStop(): Response {
        return try {
            tourRunning = false
            currentStreamName = null
            currentProtocol = null
            addLog("Tour stopped")
            onTourStop()
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject().put("success", true).toString()
            )
        } catch (e: Exception) {
            Log.e(tag, "Error stopping tour", e)
            addLog("Tour stop error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    private fun handleGetLogs(): Response {
        val logsText = synchronized(logs) {
            logs.joinToString("\n")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", logsText)
    }

    private fun serveFile(rawFilename: String): Response {
        return try {
            val normalizedPath = File("/$rawFilename").normalize().path.trimStart('/')

            if (normalizedPath.contains("..") || normalizedPath.startsWith("/")) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
            }

            val targetFile = if (normalizedPath.isEmpty()) "index.html" else normalizedPath
            val inputStream = context.assets.open(targetFile)
            val mimeType = when {
                targetFile.endsWith(".html") -> "text/html"
                targetFile.endsWith(".css") -> "text/css"
                targetFile.endsWith(".js") -> "application/javascript"
                targetFile.endsWith(".json") -> "application/json"
                targetFile.endsWith(".png") -> "image/png"
                targetFile.endsWith(".jpg") || targetFile.endsWith(".jpeg") -> "image/jpeg"
                else -> "application/octet-stream"
            }
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: Exception) {
            Log.e(tag, "Error serving file: $rawFilename", e)
            addLog("File serve error: $rawFilename - ${e.message}")
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found: $rawFilename")
        }
    }

    fun getStreamHtml(streamUrl: String, streamName: String, protocol: String): String {
        val forceWebRTC = protocol.equals("webrtc", ignoreCase = true)
        val forceMSE = protocol.equals("mse", ignoreCase = true)

        val safeStreamUrl = streamUrl.replace("\\", "\\\\").replace("'", "\\'")
        val safeStreamName = streamName.replace("\\", "\\\\").replace("'", "\\'")

        return try {
            val template = context.assets.open("stream.html").bufferedReader().use { it.readText() }
            val html = template
                .replace("{{STREAM_URL}}", safeStreamUrl)
                .replace("{{STREAM_NAME}}", safeStreamName)
                .replace("{{FORCE_WEBRTC}}", forceWebRTC.toString())
                .replace("{{FORCE_MSE}}", forceMSE.toString())

            if (html.contains("stun.l.google")) {
                Log.e(tag, "CRITICAL: HTML STILL CONTAINS STUN SERVER!")
                addLog("ERROR: STUN server found in HTML template!")
            } else {
                Log.d(tag, "STUN verification passed")
            }

            addLog("Stream HTML generated: Name: $safeStreamName, Protocol: $protocol")
            html
        } catch (e: Exception) {
            Log.e(tag, "Error loading stream template", e)
            addLog("ERROR loading stream.html: ${e.message}")
            "Error loading stream"
        }
    }
}