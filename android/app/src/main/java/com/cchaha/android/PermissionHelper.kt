package com.cchaha.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 华为 EMUI / Android 12 保活相关的用户引导：
 *  1. 电池优化白名单（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
 *  2. 通知权限（前台服务通知可见性）
 *  3. 华为「应用启动管理」手动设置引导（EMUI 特有，无法用 Intent 直达，
 *     只能跳设置首页让用户手动关闭「自动管理」）
 */
object PermissionHelper {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun ensureBatteryOptimization(activity: Activity) {
        if (isIgnoringBatteryOptimizations(activity)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                /* 无可用入口则放弃 */
            }
        }
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } catch (_: Exception) {
            }
        }
    }
}
