package com.nuvio.app.features.player

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Soia Player Integration for NuvioDesktop
 * Handles communication with Soia player via WebSocket/HTTP API
 */
internal object SoiaExternalPlayer {
    private const val DEFAULT_PORT = 17668
    private const val HEALTH_CHECK_TIMEOUT_MS = 2000L
    private const val CONNECTION_TIMEOUT_MS = 5000L
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * Check if Soia is running and available
     */
    suspend fun isAvailable(port: Int = DEFAULT_PORT): Boolean {
        return try {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port/health"))
                .timeout(java.time.Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS))
                .GET()
                .build()
            
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Send playback request to Soia via HTTP API
     */
    suspend fun sendPlaybackRequest(
        request: ExternalPlayerPlaybackRequest,
        port: Int = DEFAULT_PORT
    ): SoiaPlaybackResult {
        return try {
            val requestId = UUID.randomUUID().toString()
            
            // Build the playback request payload
            val payload = buildJsonObject {
                put("requestId", requestId)
                put("sourceUrl", request.sourceUrl)
                put("title", request.title)
                put("resumePositionMs", request.resumePositionMs)
                
                // Add headers
                if (request.sourceHeaders.isNotEmpty()) {
                    putJsonObject("headers") {
                        request.sourceHeaders.forEach { (key, value) ->
                            put(key, value)
                        }
                    }
                }
                
                // Add subtitles
                if (!request.subtitles.isNullOrEmpty()) {
                    putJsonArray("subtitles") {
                        request.subtitles.forEach { subtitle ->
                            addJsonObject {
                                put("id", subtitle.url.hashCode())
                                put("url", subtitle.url)
                                put("language", subtitle.lang)
                                put("title", subtitle.name)
                                put("isExternal", true)
                            }
                        }
                    }
                }
                
                // Add episode info if available
                if (request.season != null && request.episode != null) {
                    putJsonObject("currentEpisode") {
                        put("id", "${request.title}-S${request.season}E${request.episode}")
                        put("season", request.season)
                        put("episode", request.episode)
                        put("title", request.episodeTitle ?: "")
                        put("url", request.sourceUrl)
                    }
                }
                
                // Add skip segments if available
                if (!request.skipSegmentsJson.isNullOrBlank()) {
                    try {
                        val skipSegments = json.parseToJsonElement(request.skipSegmentsJson)
                        put("skipSegments", skipSegments)
                    } catch (e: Exception) {
                        // Ignore invalid skip segments
                    }
                }
            }
            
            // Send HTTP POST request
            val client = HttpClient.newHttpClient()
            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port/playback/load"))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofMillis(CONNECTION_TIMEOUT_MS))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()
            
            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                SoiaPlaybackResult.Success(requestId)
            } else {
                SoiaPlaybackResult.Failed("HTTP ${response.statusCode()}: ${response.body()}")
            }
        } catch (e: Exception) {
            SoiaPlaybackResult.Failed("Connection failed: ${e.message}")
        }
    }
    
    /**
     * Get current playback status from Soia
     */
    suspend fun getPlaybackStatus(port: Int = DEFAULT_PORT): SoiaPlaybackStatus? {
        return try {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port/playback/status"))
                .timeout(java.time.Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS))
                .GET()
                .build()
            
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                json.decodeFromString<SoiaPlaybackStatus>(response.body())
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Build command to launch Soia with playback request
     */
    fun buildLaunchCommand(
        soiaPath: String,
        request: ExternalPlayerPlaybackRequest,
        port: Int = DEFAULT_PORT
    ): List<String> {
        return listOf(
            soiaPath,
            "--play", request.sourceUrl,
            "--title", request.title,
            "--resume", request.resumePositionMs.toString(),
            "--port", port.toString()
        )
    }
    
    /**
     * Try to start Soia if not running
     */
    suspend fun ensureRunning(
        soiaPath: String? = null,
        port: Int = DEFAULT_PORT
    ): Boolean {
        // Check if already running
        if (isAvailable(port)) {
            return true
        }
        
        // Try to launch Soia
        if (soiaPath != null) {
            return try {
                ProcessBuilder(soiaPath, "--port", port.toString())
                    .redirectErrorStream(true)
                    .start()
                
                // Wait for Soia to start
                delay(2000)
                
                // Check again
                isAvailable(port)
            } catch (e: Exception) {
                false
            }
        }
        
        return false
    }
}

// ============================================================
// Data Classes
// ============================================================

sealed interface SoiaPlaybackResult {
    data class Success(val requestId: String) : SoiaPlaybackResult
    data class Failed(val reason: String) : SoiaPlaybackResult
}

@kotlinx.serialization.Serializable
data class SoiaPlaybackStatus(
    val requestId: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val currentSubtitleId: Int? = null,
    val currentAudioTrackId: Int? = null,
    val volume: Float = 100f,
    val isMuted: Boolean = false,
    val playbackSpeed: Float = 1f,
)

// ============================================================
// Integration with ExternalPlayerPlatform
// ============================================================

/**
 * Extension function to handle Soia player in ExternalPlayerPlatform
 */
internal fun ExternalPlayerPlatform.openWithSoia(
    request: ExternalPlayerPlaybackRequest,
    soiaPath: String? = null,
    port: Int = 17668
): ExternalPlayerOpenResult {
    return try {
        // Try to send directly to running Soia instance
        val result = runBlocking {
            SoiaExternalPlayer.sendPlaybackRequest(request, port)
        }
        
        when (result) {
            is SoiaPlaybackResult.Success -> ExternalPlayerOpenResult.Opened
            is SoiaPlaybackResult.Failed -> {
                // Try to launch Soia if not running
                val launched = runBlocking {
                    SoiaExternalPlayer.ensureRunning(soiaPath, port)
                }
                
                if (launched) {
                    // Try again
                    val retryResult = runBlocking {
                        SoiaExternalPlayer.sendPlaybackRequest(request, port)
                    }
                    
                    when (retryResult) {
                        is SoiaPlaybackResult.Success -> ExternalPlayerOpenResult.Opened
                        is SoiaPlaybackResult.Failed -> ExternalPlayerOpenResult.Failed
                    }
                } else {
                    ExternalPlayerOpenResult.Failed
                }
            }
        }
    } catch (e: Exception) {
        ExternalPlayerOpenResult.Failed
    }
}