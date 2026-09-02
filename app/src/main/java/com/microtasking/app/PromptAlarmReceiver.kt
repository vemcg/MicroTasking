package com.microtasking.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat

class PromptAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = context.getSharedPreferences("microtasking_settings", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("background_prompts_enabled", true)) return
        if (preferences.getBoolean("app_in_foreground", false)) {
            Log.i("MicroTasking", "Suppressing background prompt while app is in foreground")
            return
        }
        Log.i("MicroTasking", "Background prompt alarm received")
        PromptNotifier.show(context)
        val rapidTestMode = preferences.getBoolean("rapid_test_mode", false)
        if (preferences.getBoolean("background_prompts_enabled", true)) {
            PromptScheduler.scheduleNext(context, rapidTestMode)
        }
    }

    companion object {
        const val CHANNEL_ID = "task_prompts"
        const val PROMPT_NOTIFICATION_ID = 1001
    }
}

object PromptNotifier {
    fun show(context: Context) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                PromptAlarmReceiver.CHANNEL_ID,
                "Task prompts",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        val notification = NotificationCompat.Builder(context, PromptAlarmReceiver.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("MicroTasking")
            .setContentText("Your next task prompt is ready.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(PromptAlarmReceiver.PROMPT_NOTIFICATION_ID, notification)
    }
}

object PromptScheduler {
    private const val REQUEST_CODE = 1002

    fun scheduleNext(context: Context, rapidTestMode: Boolean) {
        val preferences = context.getSharedPreferences("microtasking_settings", Context.MODE_PRIVATE)
        if (preferences.getBoolean("app_in_foreground", false)) {
            Log.i("MicroTasking", "Skipping schedule while app is foregrounded")
            return
        }
        val delayMillis = if (rapidTestMode) 15_000L else 15 * 60 * 1_000L
        val intent = Intent(context, PromptAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + delayMillis,
            pendingIntent
        )
    }

    fun cancel(context: Context) {
        val intent = Intent(context, PromptAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        context.getSystemService(NotificationManager::class.java)
            .cancel(PromptAlarmReceiver.PROMPT_NOTIFICATION_ID)
    }
}