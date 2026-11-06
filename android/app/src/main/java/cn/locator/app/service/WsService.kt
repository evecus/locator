package cn.locator.app.service

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
import androidx.core.app.NotificationCompat
import cn.locator.app.MainActivity
import cn.locator.app.R
import cn.locator.app.net.WsEngine

/**
 * 前台服务：维持 WS 长连接。
 * 声明 foregroundServiceType="location"，这样即使 app 退到后台，
 * 收到定位请求时依然被允许获取一次位置（Android 10+ 限制的合规解法）。
 */
class WsService : Service() {

    companion object {
        private const val CHANNEL_ID = "locator_service"
        private const val NOTIF_ID = 1
        const val EXTRA_SERVER = "server"
        const val EXTRA_TOKEN = "token"

        fun start(context: Context, server: String, token: String) {
            val intent = Intent(context, WsService::class.java).apply {
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_TOKEN, token)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WsService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "位置共享连接", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val server = intent?.getStringExtra(EXTRA_SERVER).orEmpty()
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()

        startForegroundCompat()

        if (server.isNotEmpty() && token.isNotEmpty()) {
            WsEngine.connect(server, token)
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("在线，等待位置请求")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        WsEngine.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
