package dev.dblink.core.protocol

import dev.dblink.core.logging.D
import java.io.OutputStream
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

class CameraHttpGateway(
    baseUrl: String = CameraProtocolCatalog.BaseUrl,
) {
    private companion object {
        const val STREAM_COPY_BUFFER_BYTES = 256 * 1024
    }

    private val baseHttpUrl = baseUrl.toHttpUrl()
    private val baseClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .proxy(Proxy.NO_PROXY)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "DbLink/1.0")
                .build()
            chain.proceed(request)
        }
        .build()

    /**
     * Tracks the last in-flight property-change call so it can be cancelled
     * when a new one arrives. The Olympus camera HTTP server processes
     * requests serially — if a previous set_camprop is still waiting for a
     * response, the new one will time out. Cancelling the stale call
     * unblocks the socket immediately.
     */
    private val activePropertyCall = AtomicReference<Call?>(null)
    private val activeTransferCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    /**
     * Cancel any in-flight property-change call.
     * Called automatically before each [postText] (set_camprop uses POST)
     * so that rapid dial scrolling doesn't pile up stale HTTP requests.
     */
    fun cancelPendingPropertyCalls() {
        val prev = activePropertyCall.getAndSet(null)
        if (prev != null && !prev.isCanceled()) {
            D.http("CANCEL previous in-flight property call: ${prev.request().url}")
            prev.cancel()
        }
    }

    fun cancelPendingTransferCalls() {
        activeTransferCalls.toList().forEach { call ->
            if (!call.isCanceled()) {
                D.http("CANCEL in-flight transfer call: ${call.request().url}")
                call.cancel()
            }
        }
        activeTransferCalls.clear()
    }

    fun getText(
        path: String,
        query: Map<String, String> = emptyMap(),
        timeoutMillis: Int = 3_000,
    ): Result<String> = runCatching {
        val url = buildUrl(path, query)
        D.http("GET(text) $url timeout=${timeoutMillis}ms")
        val startTime = System.currentTimeMillis()
        executeRequest(path, query, timeoutMillis).use { response ->
            val elapsed = System.currentTimeMillis() - startTime
            D.http("GET(text) $path -> ${response.code} in ${elapsed}ms")
            if (!response.isSuccessful) {
                D.err("HTTP", "GET(text) FAILED: $path -> HTTP ${response.code}, headers=${response.headers}")
            }
            check(response.isSuccessful) { "Camera request failed with HTTP ${response.code}." }
            val bodyString = checkNotNull(response.body) { "Camera returned an empty response body." }.string()
            D.http("GET(text) $path body: ${bodyString.take(200)}${if (bodyString.length > 200) "..." else ""}")
            bodyString
        }
    }.onFailure {
        D.err("HTTP", "GET(text) EXCEPTION: $path", it)
    }

    fun getBytes(
        path: String,
        query: Map<String, String> = emptyMap(),
        timeoutMillis: Int = 6_000,
    ): Result<ByteArray> = runCatching {
        val url = buildUrl(path, query)
        D.http("GET(bytes) $url timeout=${timeoutMillis}ms")
        val startTime = System.currentTimeMillis()
        executeRequest(path, query, timeoutMillis).use { response ->
            val elapsed = System.currentTimeMillis() - startTime
            D.http("GET(bytes) $path -> ${response.code} in ${elapsed}ms")
            if (!response.isSuccessful) {
                D.err("HTTP", "GET(bytes) FAILED: $path -> HTTP ${response.code}")
            }
            check(response.isSuccessful) { "Camera request failed with HTTP ${response.code}." }
            val bodyBytes = checkNotNull(response.body) { "Camera returned an empty response body." }.bytes()
            D.http("GET(bytes) $path -> ${bodyBytes.size} bytes")
            bodyBytes
        }
    }.onFailure {
        D.err("HTTP", "GET(bytes) EXCEPTION: $path", it)
    }

    fun copyBytesTo(
        path: String,
        output: OutputStream,
        query: Map<String, String> = emptyMap(),
        timeoutMillis: Int = 6_000,
    ): Result<Long> {
        var call: Call? = null
        return runCatching {
            val url = buildUrl(path, query)
            D.http("GET(stream) $url timeout=${timeoutMillis}ms")
            val startTime = System.currentTimeMillis()
            val request = Request.Builder()
                .url(buildUrl(path, query))
                .get()
                .build()
            val requestCall = clientFor(timeoutMillis).newCall(request)
            call = requestCall
            activeTransferCalls += requestCall
            requestCall.execute().use { response ->
                val elapsed = System.currentTimeMillis() - startTime
                D.http("GET(stream) $path -> ${response.code} in ${elapsed}ms")
                if (!response.isSuccessful) {
                    D.err("HTTP", "GET(stream) FAILED: $path -> HTTP ${response.code}")
                }
                check(response.isSuccessful) { "Camera request failed with HTTP ${response.code}." }
                val body = checkNotNull(response.body) { "Camera returned an empty response body." }
                body.byteStream().use { input ->
                    input.copyTo(output, STREAM_COPY_BUFFER_BYTES)
                }
                val copiedBytes = body.contentLength().takeIf { it >= 0L } ?: -1L
                D.http("GET(stream) $path -> ${copiedBytes.takeIf { it >= 0L } ?: "unknown"} bytes")
                copiedBytes
            }
        }.onFailure {
            D.err("HTTP", "GET(stream) EXCEPTION: $path", it)
        }.also {
            call?.let(activeTransferCalls::remove)
        }
    }

    fun postText(
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String,
        timeoutMillis: Int = 3_000,
    ): Result<String> = runCatching {
        // Cancel any stale in-flight property call before starting a new one.
        // The camera HTTP server is serial — a stale call blocks the socket.
        cancelPendingPropertyCalls()

        val url = buildUrl(path, query)
        D.http("POST(text) $url timeout=${timeoutMillis}ms body=${body.take(100)}")
        val startTime = System.currentTimeMillis()
        val requestBody = body.toRequestBody("text/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        val call = clientFor(timeoutMillis).newCall(request)
        activePropertyCall.set(call)
        call.execute().use { response ->
            activePropertyCall.compareAndSet(call, null)
            val elapsed = System.currentTimeMillis() - startTime
            D.http("POST(text) $path -> ${response.code} in ${elapsed}ms")
            if (!response.isSuccessful) {
                D.err("HTTP", "POST(text) FAILED: $path -> HTTP ${response.code}")
            }
            check(response.isSuccessful) { "Camera request failed with HTTP ${response.code}." }
            val bodyString = checkNotNull(response.body) { "Camera returned an empty response body." }.string()
            D.http("POST(text) $path body: ${bodyString.take(200)}")
            bodyString
        }
    }.onFailure {
        activePropertyCall.set(null)
        D.err("HTTP", "POST(text) EXCEPTION: $path", it)
    }

    private fun executeRequest(
        path: String,
        query: Map<String, String>,
        timeoutMillis: Int,
    ) = clientFor(timeoutMillis).newCall(
        Request.Builder()
            .url(buildUrl(path, query))
            .get()
            .build(),
    ).execute()

    private fun clientFor(timeoutMillis: Int): OkHttpClient {
        return baseClient.newBuilder()
            .connectTimeout(minOf(timeoutMillis, 10_000).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            // callTimeout must be larger than readTimeout for large file transfers
            .callTimeout(maxOf(timeoutMillis * 3L, 120_000L), TimeUnit.MILLISECONDS)
            .build()
    }

    private fun buildUrl(path: String, query: Map<String, String>): HttpUrl {
        val builder = baseHttpUrl.newBuilder()
        path.trimStart('/')
            .split('/')
            .filter { it.isNotBlank() }
            .forEach(builder::addPathSegment)
        query.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }
        return builder.build()
    }
}
