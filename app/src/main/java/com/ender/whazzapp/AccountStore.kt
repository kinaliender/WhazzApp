package com.ender.whazzapp

import android.content.Context
import android.content.SharedPreferences
import androidx.webkit.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Bir WhatsApp hesabı = ayrı bir WebView profili (ayrı çerez / IndexedDB / önbellek).
 * İlk hesap WebView'un varsayılan profilini kullanır; böylece eski tek-hesaplı sürümdeki oturum korunur.
 */
data class Account(val id: String, val name: String) {
    val profile: String
        get() = if (id == AccountStore.DEFAULT_ID) Profile.DEFAULT_PROFILE_NAME else "acc_$id"
}

object AccountStore {
    const val DEFAULT_ID = "default"

    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_SELECTED = "selected"
    private const val KEY_BACKGROUND = "background_enabled"
    private const val KEY_ASKED_BATTERY = "asked_battery"

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val listeners = mutableSetOf<() -> Unit>()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("whazzapp", Context.MODE_PRIVATE)
        if (all().isEmpty()) {
            save(listOf(Account(DEFAULT_ID, appContext.getString(R.string.account_default_name, 1))))
        }
    }

    fun all(): List<Account> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Account(o.getString("id"), o.getString("name"))
        }
    }

    fun get(id: String?): Account? = all().firstOrNull { it.id == id }

    fun add(name: String): Account {
        val account = Account(UUID.randomUUID().toString().substring(0, 8), name)
        save(all() + account)
        return account
    }

    fun rename(id: String, name: String) {
        save(all().map { if (it.id == id) it.copy(name = name) else it })
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
        if (selectedId == id) selectedId = all().first().id
    }

    fun nextDefaultName(): String = appContext.getString(R.string.account_default_name, all().size + 1)

    var selectedId: String
        get() = prefs.getString(KEY_SELECTED, null)?.takeIf { get(it) != null } ?: all().first().id
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    /** Kullanıcı "Durdur" dediyse açılışta servisi kendiliğinden başlatma. */
    var backgroundEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND, value).apply()

    var askedBattery: Boolean
        get() = prefs.getBoolean(KEY_ASKED_BATTERY, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_BATTERY, value).apply()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun save(accounts: List<Account>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
        listeners.toList().forEach { it() }
    }
}
