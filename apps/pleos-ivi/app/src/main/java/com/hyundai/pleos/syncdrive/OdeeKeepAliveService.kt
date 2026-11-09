package com.hyundai.pleos.syncdrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 오디가 순정 내비 뒤에 있어도 프로세스(WS·SDK를 쥔 ViewModel)가 죽지 않게 하는 포그라운드 서비스.
 * ponytail: 프로세스 우선순위만 올림. 액티비티 자체가 파괴되면 세션도 끊김 → 그때 WS·SDK를 서비스로 이전.
 */
class OdeeKeepAliveService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = baseNotification(this, CHANNEL_STATUS)
            .setContentTitle("오디 그룹 주행 중")
            .setContentText("일행 위치를 공유하고 있습니다")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_STATUS, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_STATUS = "odee_status"
        private const val CHANNEL_ALERT = "odee_alert"
        private const val ID_STATUS = 1
        private const val ID_ALERT = 2

        fun start(context: Context) = context.startForegroundService(Intent(context, OdeeKeepAliveService::class.java))

        /**
         * 백그라운드 액티비티 실행이 막힐 때를 대비한 HUN + fullScreenIntent.
         * 허용되면 시스템이 바로 오디를 띄우고, 아니면 알림을 눌러 진입.
         */
        fun notifyAlert(context: Context, title: String, fullScreen: Boolean = true) {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = baseNotification(context, CHANNEL_ALERT)
                .setContentTitle(title)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setContentIntent(open)
                .apply { if (fullScreen) setFullScreenIntent(open, true) }
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(ID_ALERT, notification)
        }

        fun clearAlert(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_ALERT)

        private fun baseNotification(context: Context, channel: String): Notification.Builder {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "오디 상태", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "오디 일행 알림", NotificationManager.IMPORTANCE_HIGH))
            return Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_map)
        }
    }
}
