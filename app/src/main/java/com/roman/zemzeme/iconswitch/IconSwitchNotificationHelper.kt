package com.roman.zemzeme.iconswitch

import android.content.Context
import com.roman.zemzeme.R

object IconSwitchNotificationHelper {

    @JvmStatic
    fun getCurrentIconResId(context: Context): Int {
        val index = IconSwitchPreferences(context).currentAliasIndex
        if (index == 1) return R.mipmap.ic_launcher
        val resId = context.resources.getIdentifier("ic_icon_$index", "mipmap", context.packageName)
        return if (resId != 0) resId else R.mipmap.ic_launcher
    }
}
