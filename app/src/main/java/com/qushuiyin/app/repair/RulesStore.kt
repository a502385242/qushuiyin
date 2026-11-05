package com.qushuiyin.app.repair

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qushuiyin.app.parser.ParseRules
import kotlinx.coroutines.flow.first

private val Context.repairDataStore: DataStore<Preferences> by preferencesDataStore(name = "repair_rules")

/**
 * 规则持久化。
 *
 * 存的是**原始文本**而不是序列化后的对象：原始字节是验签的依据，
 * 存成对象再序列化回来，字节顺序、空白都变了，那份配置从此无法再自证来源。
 */
class RulesStore(private val context: Context) {

    suspend fun load(): ParseRules? = runCatching {
        val prefs = context.repairDataStore.data.first()
        val raw = prefs[KEY_RAW] ?: return@runCatching null
        RulesGate.json.decodeFromString<ParseRules>(raw)
    }.getOrNull()

    suspend fun save(text: String, rules: ParseRules, source: String) {
        // DataStore 的 edit 本身是一次原子写（先写临时文件再替换），
        // 不存在「写了一半」的状态；这里只保证四五个字段要么全有要么全无。
        context.repairDataStore.edit { prefs: MutablePreferences ->
            prefs[KEY_RAW] = text
            prefs[KEY_VERSION] = rules.schemaVersion
            prefs[KEY_SOURCE] = source
            prefs[KEY_APPLIED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun appliedVersion(): Int = runCatching {
        context.repairDataStore.data.first()[KEY_VERSION] ?: 0
    }.getOrDefault(0)

    /** 诊断信息用：这条规则是什么时候、从哪拉来的 */
    suspend fun appliedSource(): String? = runCatching {
        val prefs = context.repairDataStore.data.first()
        val source = prefs[KEY_SOURCE] ?: return@runCatching null
        val at = prefs[KEY_APPLIED_AT] ?: 0L
        "$source @ ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at))}"
    }.getOrNull()

    /** 上次预取规则的时间。用于「6 小时内不重复预取」，避免解析一次就跑一次网络 */
    suspend fun lastCheckAt(): Long = runCatching {
        context.repairDataStore.data.first()[KEY_CHECKED_AT] ?: 0L
    }.getOrDefault(0L)

    suspend fun markChecked() {
        context.repairDataStore.edit { it[KEY_CHECKED_AT] = System.currentTimeMillis() }
    }

    suspend fun clear() {
        context.repairDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_RAW = stringPreferencesKey("rules_raw")
        val KEY_VERSION = intPreferencesKey("rules_version")
        val KEY_SOURCE = stringPreferencesKey("rules_source")
        val KEY_APPLIED_AT = longPreferencesKey("rules_applied_at")
        val KEY_CHECKED_AT = longPreferencesKey("rules_checked_at")
    }
}
