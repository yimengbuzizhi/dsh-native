package xin.dshaa.nativeapp.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub 账号信息（登录后展示用；后续市场 / GitHub Models 也读这个 token）。 */
data class GitHubUser(
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
)

/**
 * GitHub 账号登录（2026-11-09 用户要求：账号区从「DeepSeek 账号」改成 GitHub 账号）。
 *
 * 用 Personal Access Token 登录：不依赖 OAuth App / 后端中转，token 只存本地
 * `dsh_github` SharedPreferences，不写日志、不进备份。
 */
class GitHubAuth private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("dsh_github", Context.MODE_PRIVATE)

    /** 已登录用户；null = 未登录。Compose 可观察。 */
    var user by mutableStateOf<GitHubUser?>(null)
        private set

    /** 当前 token（未登录时为空串）。 */
    var token: String = prefs.getString(KEY_TOKEN, "").orEmpty()
        private set

    init {
        val login = prefs.getString(KEY_LOGIN, "").orEmpty()
        if (login.isNotBlank() && token.isNotBlank()) {
            user = GitHubUser(
                login = login,
                name = prefs.getString(KEY_NAME, null),
                avatarUrl = prefs.getString(KEY_AVATAR, null),
            )
        }
    }

    /**
     * 用 PAT 调 `GET https://api.github.com/user` 验证并保存。
     * 阻塞调用，必须放到 IO 线程（UI 层用 Dispatchers.IO 包）。
     */
    fun signIn(rawToken: String): Result<GitHubUser> {
        val value = rawToken.trim()
        if (value.isBlank()) return Result.failure(IllegalArgumentException("token 为空"))
        return runCatching {
            val conn = (URL("https://api.github.com/user").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $value")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "whale-song")
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    error("GitHub 返回 HTTP $code：${body.take(160)}")
                }
                val json = JSONObject(body)
                val login = json.optString("login")
                if (login.isBlank()) error("GitHub 返回里没有 login")
                val parsed = GitHubUser(
                    login = login,
                    name = json.optString("name").takeIf { it.isNotBlank() },
                    avatarUrl = json.optString("avatar_url").takeIf { it.isNotBlank() },
                )
                token = value
                user = parsed
                prefs.edit()
                    .putString(KEY_TOKEN, value)
                    .putString(KEY_LOGIN, parsed.login)
                    .putString(KEY_NAME, parsed.name)
                    .putString(KEY_AVATAR, parsed.avatarUrl)
                    .apply()
                parsed
            } finally {
                conn.disconnect()
            }
        }
    }

    fun signOut() {
        token = ""
        user = null
        prefs.edit().clear().apply()
    }

    companion object {
        @Volatile
        private var instance: GitHubAuth? = null

        fun get(context: Context): GitHubAuth =
            instance ?: synchronized(this) {
                instance ?: GitHubAuth(context).also { instance = it }
            }

        private const val KEY_TOKEN = "token"
        private const val KEY_LOGIN = "login"
        private const val KEY_NAME = "name"
        private const val KEY_AVATAR = "avatar_url"
    }
}
