package com.roman.zemzeme.iconswitch

import android.content.Context
import android.content.SharedPreferences

internal class IconSwitchPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("icon_switch_prefs", Context.MODE_PRIVATE)

    var currentAliasIndex: Int
        get() = prefs.getInt(KEY_CURRENT_ALIAS, DEFAULT_ALIAS)
        set(value) = prefs.edit().putInt(KEY_CURRENT_ALIAS, value).apply()

    companion object {
        private const val KEY_CURRENT_ALIAS = "current_alias_index"
        const val DEFAULT_ALIAS = 1
    }
}
