package com.lonx.lyrico.plugin.source.native

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * 内置原生源共用的 HTTP 通道。
 *
 * 与插件运行时（QuickJS）不同，内置源直接跑在宿主进程里，因此必须自己守住四条底线：
 * 1. **超时分级**：境外源给足时间，境内源快速失败，避免批量匹配时被单个慢源拖住；
 * 2. **异常不外泄**：任何网络/解析失败一律返回 null，由调用方转成空结果，
 *    批量任务不会因为一个源挂掉就整批失败（与 MusicOrganizer 的 safeRun 语义一致）；
 * 3. **主机级熔断**：402/403/405/5xx 连续失败后按主机冷却，
 *    冷却期内**不发请求直接返回**（移植自源实现的 `Http.HostState`）；
 * 4. **UA 可识别**：MusicBrainz 明确要求带可联系的 UA，否则会被封。
 */
class NativeHttp(
    private val baseClient: OkHttpClient,
    private val parser: Json = defaultParser
) {

    /** 按超时时间复用 OkHttpClient，避免每次请求都新 builder 一遍连接池。 */
    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    /** 按主机累计失败次数与冷却截止时间。批量任务会反复打同一批域名，这个表是全局限流的关键。 */
    private val hosts = ConcurrentHashMap<String, HostState>()

    private class HostState {
        val fails = AtomicInteger(0)

        @Volatile
        var cooldownUntil: Long = 0L

        @Volatile
        var reason: String = ""
    }

    private fun stateOf(host: String): HostState = hosts.computeIfAbsent(host) { HostState() }

    private fun noteOk(state: HostState) {
        state.fails.set(0)
        state.cooldownUntil = 0L
        state.reason = ""
    }

    private fun noteFail(state: HostState, reason: String, cooldownMs: Long) {
        state.reason = reason
        if (state.fails.incrementAndGet() >= FAIL_THRESHOLD) {
            state.cooldownUntil = System.currentTimeMillis() + cooldownMs
            state.fails.set(0)
        }
    }

    private fun clientFor(timeoutMs: Long): OkHttpClient =
        clients.getOrPut(timeoutMs) {
            baseClient.newBuilder()
                .connectTimeout(minOf(timeoutMs, CONNECT_TIMEOUT_MS), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + CALL_TIMEOUT_GRACE_MS, TimeUnit.MILLISECONDS)
                .build()
        }

    suspend fun getText(
        url: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = TIMEOUT_OVERSEAS_MS
    ): String? = withContext(Dispatchers.IO) {
        val builtUrl = buildUrl(url, params)
        if (builtUrl == null) {
            Log.w(TAG, "skip request: malformed url=$url")
            return@withContext null
        }
        val host = builtUrl.host
        val state = stateOf(host)

        // 冷却期内直接返回，连 DNS 都不查——这正是「一个源挂了不拖慢整批」的关键
        val now = System.currentTimeMillis()
        if (state.cooldownUntil > now) {
            val secondsLeft = (state.cooldownUntil - now) / 1000 + 1
            Log.d(TAG, "skip $host: cooling down ${secondsLeft}s (${state.reason})")
            return@withContext null
        }

        try {
            val request = Request.Builder()
                .url(builtUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            clientFor(timeoutMs).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    handleFailureStatus(state, host, response.code)
                    // 404 在歌词库里等于「没有这首」，不是故障，调用方会走回退逻辑
                    null
                } else {
                    noteOk(state)
                    // OkHttp 5 起 Response.body 非空，无需再判空
                    response.body.string()
                }
            }
        } catch (throwable: Exception) {
            if (throwable is CancellationException) throw throwable
            noteFail(state, throwable.javaClass.simpleName, COOLDOWN_MS)
            Log.w(TAG, "request failed: $host${builtUrl.encodedPath} ${throwable.message}")
            null
        }
    }

    /**
     * 按状态码决定要不要记一次失败。
     *
     * 403/405 在国内几家音乐站上就是「请求太频繁」的另一种说法（网易直接回
     * `405 操作频繁`），和 429 一样按限流处理并给更长的冷却；
     * 5xx 是服务端问题，短冷却即可。404 与其它 4xx 属于「这次查询本身没结果」，不计入。
     */
    private fun handleFailureStatus(state: HostState, host: String, code: Int) {
        when {
            code == 404 -> Log.d(TAG, "not found: $host")
            code == 429 || code == 403 || code == 405 -> {
                noteFail(state, "HTTP $code 限流", RATE_LIMIT_COOLDOWN_MS)
                Log.w(TAG, "rate limited ($code): $host")
            }
            code >= 500 -> {
                noteFail(state, "HTTP $code", COOLDOWN_MS)
                Log.w(TAG, "server error ($code): $host")
            }
            else -> Log.d(TAG, "HTTP $code: $host")
        }
    }

    suspend fun getJson(
        url: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = TIMEOUT_OVERSEAS_MS
    ): JsonElement? {
        val body = getText(url, params, headers, timeoutMs) ?: return null
        return runCatching { parser.parseToJsonElement(body) }
            .onFailure { Log.d(TAG, "json parse failed: ${body.take(120)}") }
            .getOrNull()
    }

    private fun buildUrl(url: String, params: Map<String, String>): HttpUrl? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (params.isEmpty()) return parsed
        return parsed.newBuilder().apply {
            params.forEach { (key, value) ->
                if (value.isNotEmpty()) addQueryParameter(key, value)
            }
        }.build()
    }

    companion object {
        private const val TAG = "NativeHttp"

        /** 境外源（MusicBrainz / Deezer / iTunes / LRCLIB）。 */
        const val TIMEOUT_OVERSEAS_MS = 8_000L

        /** 境内源（网易 / QQ / 酷狗 / 咪咕）同时发，慢的直接放弃。 */
        const val TIMEOUT_DOMESTIC_MS = 5_000L

        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val CALL_TIMEOUT_GRACE_MS = 2_000L

        /** 连续失败几次后熔断。设 2 是为了不被偶发的抖动触发。 */
        private const val FAIL_THRESHOLD = 2

        /** 服务端故障（5xx / 连接异常）的冷却时长。 */
        private const val COOLDOWN_MS = 45_000L

        /** 被限流的冷却时长；比普通故障更长，因为限流往往以分钟计。 */
        private const val RATE_LIMIT_COOLDOWN_MS = 90_000L

        private const val USER_AGENT =
            "LyricoX/0.01 (+https://github.com/kakadex/Lyrico)"

        val defaultParser: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}

// ==================== JsonElement 取值小工具 ====================
// kotlinx.serialization 的 JsonElement 没有 optString 之类的宽松取值，这里补一层，
// 让移植过来的源代码保持和 org.json 版本一致的写法（缺失/类型不符一律退化成空值）。

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

internal fun JsonObject.array(key: String): List<JsonElement> =
    (this[key] as? JsonArray)?.toList().orEmpty()

internal fun JsonObject.str(vararg keys: String): String {
    for (key in keys) {
        val primitive = this[key] as? JsonPrimitive ?: continue
        val value = primitive.contentOrNull?.trim().orEmpty()
        if (value.isNotEmpty() && value != "null") return value
    }
    return ""
}

internal fun JsonObject.longOrNull(vararg keys: String): Long? {
    for (key in keys) {
        val primitive = this[key] as? JsonPrimitive ?: continue
        primitive.contentOrNull?.trim()?.toLongOrNull()?.let { return it }
        primitive.contentOrNull?.trim()?.toDoubleOrNull()?.let { return it.toLong() }
    }
    return null
}

internal fun JsonObject.doubleOrNull(vararg keys: String): Double? {
    for (key in keys) {
        val primitive = this[key] as? JsonPrimitive ?: continue
        primitive.contentOrNull?.trim()?.toDoubleOrNull()?.let { return it }
    }
    return null
}

internal fun JsonObject.boolOrNull(vararg keys: String): Boolean? {
    for (key in keys) {
        val primitive = this[key] as? JsonPrimitive ?: continue
        when (primitive.contentOrNull?.trim()?.lowercase()) {
            "true", "1" -> return true
            "false", "0" -> return false
        }
    }
    return null
}

/** 秒 → 毫秒，顺手挡掉负数与明显异常值。 */
internal fun secondsToMillis(seconds: Double?): Long {
    if (seconds == null || seconds <= 0.0) return 0L
    return (seconds * 1000.0).toLong()
}
