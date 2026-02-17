package com.roman.zemzeme.features.sharing

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.roman.zemzeme.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ApkSharingHelper {
    private const val TAG = "ApkSharingHelper"

    fun getApkSourcePath(context: Context): String? {
        return try {
            context.applicationInfo.sourceDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get APK source path: ${e.message}")
            null
        }
    }

    fun getApkSizeBytes(context: Context): Long {
        val path = getApkSourcePath(context) ?: return 0L
        return try {
            File(path).length()
        } catch (e: Exception) {
            0L
        }
    }

    private fun copyApkToShareableLocation(context: Context): File? {
        val sourcePath = getApkSourcePath(context) ?: return null
        return try {
            val cacheDir = File(context.cacheDir, "share_apk").apply { mkdirs() }
            val appName = context.getString(R.string.app_name)
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) {
                "unknown"
            }

            val destFile = File(cacheDir, "${appName}-${versionName}.apk")
            val sourceFile = File(sourcePath)

            if (destFile.exists() && destFile.length() == sourceFile.length()) {
                return destFile
            }

            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied APK to ${destFile.absolutePath} (${destFile.length()} bytes)")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy APK for sharing: ${e.message}", e)
            null
        }
    }

    fun createShareIntent(context: Context): Intent? {
        val apkFile = copyApkToShareableLocation(context) ?: return null
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_SUBJECT, "${context.getString(R.string.app_name)} App")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create share intent: ${e.message}", e)
            null
        }
    }
}
