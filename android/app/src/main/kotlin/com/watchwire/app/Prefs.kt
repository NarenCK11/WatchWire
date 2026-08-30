package com.watchwire.app

import android.content.Context
import android.content.SharedPreferences

/** Small SharedPreferences wrapper for the handful of values that need to survive process
 * death: the reconnect token for our camera session and the user-configured backend URL. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("watchwire_prefs", Context.MODE_PRIVATE)

    var cameraToken: String?
        get() = sp.getString(KEY_CAMERA_TOKEN, null)
        set(value) = sp.edit().putString(KEY_CAMERA_TOKEN, value).apply()

    var wsBaseUrl: String
        get() = sp.getString(KEY_WS_BASE_URL, BuildConfig.DEFAULT_WS_URL) ?: BuildConfig.DEFAULT_WS_URL
        set(value) = sp.edit().putString(KEY_WS_BASE_URL, value).apply()

    var sensitivity: Float
        get() = sp.getFloat(KEY_SENSITIVITY, 0.5f)
        set(value) = sp.edit().putFloat(KEY_SENSITIVITY, value).apply()

    companion object {
        private const val KEY_CAMERA_TOKEN = "camera_token"
        private const val KEY_WS_BASE_URL = "ws_base_url"
        private const val KEY_SENSITIVITY = "sensitivity"
    }
}
