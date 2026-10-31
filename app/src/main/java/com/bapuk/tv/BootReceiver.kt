package com.bapuk.tv

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Открывает BapukTV после загрузки устройства (настройка «Запускать при включении ТВ»).
 * Приёмник включается только вместе с настройкой, чтобы не будить приложение зря.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS || !Repo.autoStartOnBoot) return
        val pending = goAsync()
        // Небольшая пауза: даём системе и лаунчеру ТВ закончить загрузку, иначе лаунчер может перекрыть нас
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w("BapukTV", "Не удалось открыть приложение при загрузке", e)
            } finally {
                pending.finish()
            }
        }, START_DELAY_MS)
    }

    companion object {
        private const val START_DELAY_MS = 2_000L
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )

        fun setEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BootReceiver::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        /**
         * С Android 10 приложение может открыться само только с разрешением
         * «Поверх других приложений».
         */
        fun needsOverlayPermission(context: Context) =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)
    }
}
