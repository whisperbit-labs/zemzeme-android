package com.roman.zemzeme.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class UpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                    try { context.startActivity(it) } catch (e: Exception) { Log.e(TAG, "Failed to launch confirmation", e) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateManager.getInstanceOrNull()?.onInstallStatusReceived(status, message)
                restartApp(context)
                return
            }
            else -> Unit
        }
        UpdateManager.getInstanceOrNull()?.onInstallStatusReceived(status, message)
    }

    private fun restartApp(context: Context) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(intent)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart app", e)
            }
        }, 500)
    }
}
