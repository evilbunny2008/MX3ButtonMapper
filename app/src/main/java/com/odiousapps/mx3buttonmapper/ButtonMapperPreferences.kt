package com.odiousapps.mx3buttonmapper

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "button_mapper_settings")

/** Which TV this is running against -- affects scancode 419's (the MX3
 *  Air Mouse's TV button) target keycode specifically, since different
 *  TVs have turned out to expect different keycodes for it (see
 *  ButtonMapperService.kt's handling of this scancode). */
enum class TvBrand {
    TCL,
    BLAUPUNKT,
}

/**
 * Stores the auth token Shizuku's "Start/stop intents" automation
 * feature requires as a broadcast extra. Kept in local app settings
 * rather than hardcoded in source, since this is a real per-install
 * credential and this app's source is public (F-Droid).
 */
object ButtonMapperPreferences {

    private val KEY_SHIZUKU_AUTH_TOKEN = stringPreferencesKey("shizuku_auth_token")
    private val KEY_TV_BRAND = stringPreferencesKey("tv_brand")

    fun observeShizukuAuthToken(context: Context): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[KEY_SHIZUKU_AUTH_TOKEN] ?: "" }

    suspend fun setShizukuAuthToken(context: Context, token: String) {
        context.dataStore.edit { it[KEY_SHIZUKU_AUTH_TOKEN] = token }
    }

    /** Blocking read for use from non-Compose contexts (the accessibility
     *  service itself, which isn't Compose-based) where collecting a
     *  Flow isn't practical -- this is a single small string read on
     *  service startup, not a hot path, so the blocking read is fine here.
     *  Flow.first() collects just the first emitted value and cancels
     *  the flow cleanly, no manual collection loop needed. */
    fun getShizukuAuthTokenBlocking(context: Context): String =
        kotlinx.coroutines.runBlocking {
            observeShizukuAuthToken(context).first()
        }

    // TCL is the default -- matches the current, already-shipped
    // behaviour for anyone upgrading without having touched this
    // setting yet, rather than silently changing existing behaviour
    // out from under them.
    fun observeTvBrand(context: Context): Flow<TvBrand> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_TV_BRAND]?.let { runCatching { TvBrand.valueOf(it) }.getOrNull() } ?: TvBrand.TCL
        }

    suspend fun setTvBrand(context: Context, brand: TvBrand) {
        context.dataStore.edit { it[KEY_TV_BRAND] = brand.name }
    }
}
