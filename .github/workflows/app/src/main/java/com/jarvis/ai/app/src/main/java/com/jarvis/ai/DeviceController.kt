package com.jarvis.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.util.Locale

class DeviceController(private val context: Context) {

    fun handleCommand(rawCommand: String): String? {
        val c = rawCommand.lowercase(Locale.getDefault()).trim()

        // 1. Flashlight
        if ((c.contains("flashlight") || c.contains("torch") || c.contains("light")) && c.contains("on")) {
            return setFlashlight(true)
        }
        if ((c.contains("flashlight") || c.contains("torch") || c.contains("light")) && c.contains("off")) {
            return setFlashlight(false)
        }

        // 2. Battery status
        if (c.contains("battery") || c.contains("charge")) {
            return getBatteryStatus()
        }

        // 3. Mobile performance (RAM & Storage)
        if (c.contains("performance") || c.contains("ram") || c.contains("memory") ||
            c.contains("storage") || c.contains("system status") || c.contains("diagnostics")) {
            return getPerformanceStatus()
        }

        // 4. Search Google / Web
        if (c.startsWith("search google for ") || c.startsWith("search for ") || c.startsWith("google ")) {
            val query = c.removePrefix("search google for ")
                .removePrefix("search for ")
                .removePrefix("google ")
                .trim()
            if (query.isNotEmpty()) {
                openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
                return "Searching Google for $query, boss."
            }
        }

        // 5. Open websites or installed apps
        if (c.startsWith("open ")) {
            val target = c.removePrefix("open ").trim()
            if (target.contains(".com") || target.contains(".org") || target.contains(".in") || target.startsWith("http")) {
                val url = if (target.startsWith("http")) target else "https://$target"
                openUrl(url)
                return "Opening $target, boss."
            }
            return openAppByName(target)
        }

        return null // Not a local device command; send to Gemini AI
    }

    private fun setFlashlight(turnOn: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight hardware detected on this device, boss."

            cameraManager.setTorchMode(cameraId, turnOn)
            if (turnOn) "Flashlight engaged, boss." else "Flashlight powered down, boss."
        } catch (e: Exception) {
            "Unable to control the flashlight right now, boss."
        }
    }

    private fun getBatteryStatus(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0) (level * 100) / scale else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        val stateText = if (charging) "charging" else "on battery power"
        return "Power is at $pct percent, $stateText, with battery temperature at $tempC degrees Celsius, boss."
    }

    private fun getPerformanceStatus(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val usedRamPct = (((memInfo.totalMem - memInfo.availMem).toDouble() / memInfo.totalMem) * 100).toInt()

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeStorageGb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
        val totalStorageGb = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)

        return String.format(
            Locale.getDefault(),
            "System diagnostics: RAM is at %d percent used, with %.1f of %.1f gigabytes free. Internal storage has %.1f of %.1f gigabytes available.",
            usedRamPct, availRamGb, totalRamGb, freeStorageGb, totalStorageGb
        )
    }

    private fun openAppByName(appName: String): String {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(launcherIntent, 0)
        val match = apps.firstOrNull { info ->
            info.loadLabel(pm).toString().lowercase(Locale.getDefault()).contains(appName)
        }

        if (match != null) {
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return "Launching ${match.loadLabel(pm)}, boss."
            }
        }
        return "I couldn't find an installed app named $appName, boss."
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
