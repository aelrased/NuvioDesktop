package com.nuvio.app.features.addons

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal actual object AddonStorage {
    private val store = DesktopStorage.store("nuvio_addons")
    private val json = Json { ignoreUnknownKeys = true }

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        store.getString("installed_addon_urls_$profileId")
            ?.let { payload -> runCatching { json.decodeFromString<List<String>>(payload) }.getOrNull() }
            ?: emptyList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        store.putString("installed_addon_urls_$profileId", json.encodeToString(urls))
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        store.getString("addon_enabled_states_$profileId")
            ?.let { payload -> runCatching { json.decodeFromString<Map<String, Boolean>>(payload) }.getOrNull() }
            ?: emptyMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        store.putString("addon_enabled_states_$profileId", json.encodeToString(states))
    }
}

private val desktopHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private const val truncationSuffix = "\n...[truncated]"

actual suspend fun httpGetText(url: String): String =
    httpGetTextWithHeaders(url, mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"))

actual suspend fun httpPostJson(url: String, body: String): String =
    httpPostJsonWithHeaders(url, body, mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"))

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String =
    httpRequestRaw("GET", url, headers, body = "").body

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    httpRequestRaw(
        method = "POST",
        url = url,
        headers = mapOf("Content-Type" to "application/json") + headers,
        body = body,
    ).body

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
    maxResponseBodyBytes: Int,
): RawHttpResponse = withContext(Dispatchers.IO) {
    val client = if (followRedirects) {
        desktopHttpClient
    } else {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    val requestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(30))

    headers.filterNot { it.key.equals("Accept-Encoding", ignoreCase = true) }
        .forEach { (k, v) -> requestBuilder.header(k, v) }

    when (method.uppercase()) {
        "GET" -> requestBuilder.GET()
        "POST", "PUT", "PATCH", "DELETE" -> {
            requestBuilder.method(method.uppercase(), HttpRequest.BodyPublishers.ofString(body))
        }
        else -> requestBuilder.method(method.uppercase(), HttpRequest.BodyPublishers.noBody())
    }

    val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

    val responseBody = readAtMostBytesLimited(response.body(), maxResponseBodyBytes)
    val charset = response.headers().firstValue("Content-Type")
        .orElse(null)
        ?.let { ct ->
            Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
        }?.let { charsetName ->
            runCatching { java.nio.charset.Charset.forName(charsetName) }.getOrNull()
        } ?: Charsets.UTF_8

    val decoded = runCatching {
        String(responseBody.bytes, charset)
    }.getOrElse {
        String(responseBody.bytes, Charsets.UTF_8)
    }
    val bodyStr = if (responseBody.truncated) decoded + truncationSuffix else decoded

    RawHttpResponse(
        status = response.statusCode(),
        statusText = "HTTP ${response.statusCode()}",
        url = url,
        body = bodyStr,
        headers = response.headers().map().mapValues { (_, values) ->
            values.joinToString(",")
        }.mapKeys { (name, _) ->
            name.lowercase()
        },
    )
}

private fun readAtMostBytesLimited(stream: InputStream, maxBytes: Int): LimitedReadResult {
    return if (maxBytes > 0) readAtMostBytes(stream, maxBytes) else LimitedReadResult(stream.readBytes(), truncated = false)
}

private fun requestAllowsBody(method: String): Boolean =
    when (method.uppercase()) {
        "POST", "PUT", "PATCH", "DELETE" -> true
        else -> false
    }

private fun Map<String, String>.withoutAcceptEncoding(): Map<String, String> =
    entries
        .filterNot { (key, _) -> key.equals("Accept-Encoding", ignoreCase = true) }
        .associate { (key, value) -> key to value }

private fun Map<String, String>.getHeaderIgnoreCase(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

private data class LimitedReadResult(
    val bytes: ByteArray,
    val truncated: Boolean,
)

private fun readAtMostBytes(stream: InputStream, maxBytes: Int): LimitedReadResult {
    val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    var truncated = false

    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }

    if (remaining == 0) {
        truncated = stream.read() != -1
    }

    return LimitedReadResult(out.toByteArray(), truncated)
}
