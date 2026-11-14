package xin.dshaa.nativeapp.remote

import android.content.SharedPreferences
import kotlinx.serialization.json.*
import xin.dshaa.ui.shellapi.remote.RemoteProfileUi
import java.net.URI
import java.util.UUID

/** Atomic preference boundary, also usable without Android in fixture tests. */
internal interface RemoteProfilePreferences {
    fun string(key: String): String?
    fun long(key: String): Long
    fun commit(values: Map<String, String>, removed: Set<String>): Boolean
}

private class AndroidRemoteProfilePreferences(private val prefs: SharedPreferences) : RemoteProfilePreferences {
    override fun string(key: String): String? = prefs.getString(key, null)
    override fun long(key: String): Long = prefs.getLong(key, 0L)
    override fun commit(values: Map<String, String>, removed: Set<String>): Boolean = prefs.edit().apply {
        values.forEach { (key, value) -> putString(key, value) }
        removed.forEach { remove(it) }
    }.commit()
}

/**
 * Metadata and tokens use separate keys, bound by stable profile UUID, never URL/list position.
 * Migration writes the new metadata/token and removes legacy keys in one committed transaction.
 * No network or session persistence is performed here.
 */
internal class RemoteProfileStore(
    private val prefs: RemoteProfilePreferences,
    private val tolerateCorruption: Boolean = false,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(prefs: SharedPreferences, tolerateCorruption: Boolean = false) :
        this(AndroidRemoteProfilePreferences(prefs), tolerateCorruption)

    private data class Profile(val id: String, val name: String, val baseUrl: String, val lastConnectedAt: Long)
    private var records: List<Profile>
    var activeId: String?
        private set
    var loadError: String? = null
        private set

    init {
        val saved = prefs.string(METADATA)
        if (saved == null) {
            val id = newId()
            val initial = Profile(id, "远程 DSH", normalizeBase(prefs.string(LEGACY_URL).orEmpty()), prefs.long(LEGACY_LAST_OK))
            records = listOf(initial)
            activeId = id
            val token = prefs.string(LEGACY_TOKEN).orEmpty().trim()
            persist(records, id, mapOf(tokenKey(id) to token), setOf(LEGACY_URL, LEGACY_TOKEN, LEGACY_LAST_OK))
        } else {
            // Corruption is not a reason to import legacy credentials again or overwrite the file.
            try {
                val obj = Json.parseToJsonElement(saved).jsonObject
                require(obj["version"]?.jsonPrimitive?.intOrNull == 1) { "远程配置版本无效" }
                val rows = obj["profiles"] as? JsonArray ?: error("远程配置清单损坏")
                records = rows.map { element ->
                    val row = element.jsonObject
                    Profile(row.text("id"), row.text("name"), row.text("baseUrl"), row["lastConnectedAt"]?.jsonPrimitive?.longOrNull ?: 0)
                }
                require(records.map { it.id }.distinct().size == records.size && records.all { validId(it.id) }) { "远程配置清单损坏" }
                activeId = obj["activeId"]?.jsonPrimitive?.contentOrNull?.takeIf { id -> records.any { it.id == id } }
            } catch (e: Exception) {
                if (!tolerateCorruption) throw e
                records = emptyList(); activeId = null
                loadError = "远程配置清单损坏，原数据已保留；可在远程管理中重新保存配置"
            }
        }
    }

    @Synchronized fun profiles(): List<RemoteProfileUi> = records.map {
        RemoteProfileUi(it.id, it.name, it.baseUrl, !token(it.id).isNullOrBlank(), it.lastConnectedAt)
    }

    @Synchronized fun token(id: String?): String? = id?.takeIf { candidate -> records.any { it.id == candidate } }
        ?.let { prefs.string(tokenKey(it)).orEmpty() }

    @Synchronized fun select(id: String): Boolean {
        if (records.none { it.id == id }) return false
        if (id != activeId) { persist(records, id); activeId = id }
        return true
    }

    @Synchronized fun save(id: String?, name: String, baseUrl: String, token: String?): String {
        val old = id?.let { wanted -> records.firstOrNull { it.id == wanted } ?: error("远程配置不存在") }
        require(old != null || records.size < 32) { "最多保存 32 个远程配置" }
        val normalized = normalizeBase(baseUrl)
        require(normalized.isEmpty() || validBase(normalized)) { "地址必须是没有账号、查询参数和片段的 http(s) 地址" }
        val identity = old?.id ?: newId().also { candidate ->
            require(validId(candidate) && records.none { it.id == candidate }) { "远程配置 id 冲突" }
        }
        val cleanName = name.trim().take(120).ifBlank { "远程 DSH" }
        // An endpoint edit cannot accidentally reuse the old endpoint's token.
        val changedAddress = old != null && old.baseUrl != normalized
        val effectiveToken = token?.trim() ?: if (changedAddress) "" else this.token(identity).orEmpty()
        require(effectiveToken.length <= 8_192) { "令牌太长" }
        val next = Profile(identity, cleanName, normalized, if (changedAddress) 0 else old?.lastConnectedAt ?: 0)
        val updated = if (old == null) records + next else records.map { if (it.id == identity) next else it }
        val selected = activeId ?: identity
        persist(updated, selected, mapOf(tokenKey(identity) to effectiveToken))
        records = updated; activeId = selected; loadError = null
        return identity
    }

    @Synchronized fun remove(id: String): Boolean {
        if (records.none { it.id == id }) return false
        val updated = records.filterNot { it.id == id }
        val selected = if (activeId == id) updated.firstOrNull()?.id else activeId
        persist(updated, selected, removed = setOf(tokenKey(id)))
        records = updated; activeId = selected
        return true
    }

    @Synchronized fun noteConnected(id: String, at: Long) {
        val updated = records.map { if (it.id == id) it.copy(lastConnectedAt = at) else it }
        persist(updated, activeId); records = updated
    }

    private fun persist(rows: List<Profile>, active: String?, tokens: Map<String, String> = emptyMap(), removed: Set<String> = emptySet()) {
        val metadata = buildJsonObject {
            put("version", 1); put("activeId", active)
            put("profiles", JsonArray(rows.map { row -> buildJsonObject {
                put("id", row.id); put("name", row.name); put("baseUrl", row.baseUrl); put("lastConnectedAt", row.lastConnectedAt)
            } }))
        }.toString()
        check(prefs.commit(tokens + (METADATA to metadata), removed)) { "远程配置保存失败" }
    }

    companion object {
        internal const val METADATA = "remote_dsh_profiles_v1"
        internal const val LEGACY_URL = "remote_dsh_url"
        internal const val LEGACY_TOKEN = "remote_dsh_token"
        internal const val LEGACY_LAST_OK = "remote_dsh_last_ok"
        internal fun tokenKey(id: String) = "remote_dsh_profile_token_$id"
        fun normalizeBase(raw: String): String {
            val clean = raw.trim().trimEnd('/')
            if (clean.isEmpty()) return ""
            return if (clean.contains("://")) clean else "http://$clean"
        }
        internal fun validBase(base: String): Boolean = runCatching {
            val uri = URI(base)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.port in -1..65535 &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
        private fun validId(id: String) = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$").matches(id)
        private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
