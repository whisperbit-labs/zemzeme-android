package com.roman.zemzeme.iconswitch

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.core.content.ContextCompat

internal class IconSwitchTaskDescription : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        val prefs = IconSwitchPreferences(activity)
        val index = prefs.currentAliasIndex
        val name = IconSwitcher.NAMES.getOrNull(index - 1) ?: return
        val resId = if (index == 1) {
            com.roman.zemzeme.R.mipmap.ic_launcher
        } else {
            activity.resources.getIdentifier(
                "ic_icon_$index", "mipmap", activity.packageName
            )
        }
        if (resId == 0) return

        val drawable = ContextCompat.getDrawable(activity, resId) ?: return
        val size = (48 * activity.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)

        activity.setTaskDescription(ActivityManager.TaskDescription(name, bitmap))
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
