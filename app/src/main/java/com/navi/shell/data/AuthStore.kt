package com.navi.shell.data

import android.content.Context

/** gate_auth cookie 的落地处。 */
class AuthStore(context: Context) {

    private val prefs = context.getSharedPreferences("navi_shell_auth", Context.MODE_PRIVATE)

    /** 形如 "gate_auth=ose"。 */
    var cookie: String
        get() = prefs.getString(KEY_COOKIE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    var isAuthorized: Boolean
        get() = prefs.getBoolean(KEY_AUTHORIZED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTHORIZED, value).apply()

    private companion object {
        const val KEY_COOKIE = "cookie"
        const val KEY_AUTHORIZED = "authorized"
    }
}
