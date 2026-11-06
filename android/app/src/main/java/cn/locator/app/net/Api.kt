package cn.locator.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class AuthResp(val token: String, val user_id: Long, val username: String)

@Serializable
data class ApiError(val error: String? = null)

/** 地址归一化 */
fun normalizeBase(input: String): String {
    var b = input.trim().trimEnd('/')
    if (b.isEmpty()) return b
    if (!b.startsWith("http://") && !b.startsWith("https://") &&
        !b.startsWith("ws://") && !b.startsWith("wss://")
    ) {
        b = "https://$b"
    }
    return b
}

fun wsUrlOf(base: String, token: String): String {
    val b = normalizeBase(base)
        .replace("http://", "ws://")
        .replace("https://", "wss://")
    val path = if (b.endsWith("/ws")) b else "$b/ws"
    return "$path?token=$token"
}

fun apiBaseOf(base: String): String = normalizeBase(base).let {
    it.replace("ws://", "http://").replace("wss://", "https://")
}

object Api {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun register(
        server: String,
        username: String,
        password: String,
        invite: String,
    ): Result<AuthResp> = post(
        server, "/api/register",
        """{"username":"${esc(username)}","password":"${esc(password)}","invite_code":"${esc(invite)}"}""",
    )

    suspend fun login(server: String, username: String, password: String): Result<AuthResp> =
        post(
            server, "/api/login",
            """{"username":"${esc(username)}","password":"${esc(password)}"}""",
        )

    private fun esc(s: String) =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private suspend fun post(server: String, path: String, body: String): Result<AuthResp> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(apiBaseOf(server) + path)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = runCatching { json.decodeFromString<ApiError>(text).error }
                            .getOrNull() ?: "HTTP ${resp.code}"
                        error(msg)
                    }
                    json.decodeFromString<AuthResp>(text)
                }
            }
        }
}

/** 登录态持久化 */
object Prefs {
    private const val NAME = "locator"

    fun load(ctx: Context): Stored? {
        val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE) ?: return null
        val token = sp.getString("token", null) ?: return null
        return Stored(
            server = sp.getString("server", "").orEmpty(),
            username = sp.getString("username", "").orEmpty(),
            token = token,
            userId = sp.getLong("user_id", 0L),
        )
    }

    fun save(ctx: Context, server: String, resp: AuthResp) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("server", server)
            .putString("username", resp.username)
            .putString("token", resp.token)
            .putLong("user_id", resp.user_id)
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

data class Stored(val server: String, val username: String, val token: String, val userId: Long)
