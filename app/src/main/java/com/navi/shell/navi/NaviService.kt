package com.navi.shell.navi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.navi.shell.MainActivity
import com.navi.shell.R

/**
 * 导航期间挂个前台服务，就为了两件事：
 *  1. 别让系统在切走 App / 锁屏之后把进程掐了 —— 掐了就不报转向了，开车时这是要命的事
 *  2. 留一条「正在给你看着路」的通知，随时点回来看
 *
 * 它不干活，只是个「我还活着」的牌子。定位和算路都在 AMapNavi 那边。
 */
class NaviService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // 被系统回收过再回来时，别自动重开 —— 导航早结束了，没意义
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "导航中",
                        NotificationManager.IMPORTANCE_LOW,   // 别响，别震，别打断播报
                    ).apply {
                        description = "导航进行时挂在这儿，让你随时能切回来"
                        setShowBadge(false)
                    }
                )
            }
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // 名字留空就只说事，不挂名字（见 strings.xml 的 ai_name）
        val who = getString(R.string.ai_name)
        val title = if (who.isBlank()) "正在给你看着路" else "$who 在给你看着路"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("点一下切回导航")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "navi_running"
        private const val NOTIFICATION_ID = 4711

        /** 开始导航时叫起来。从界面（前台）调，所以不会撞 Android 12+ 的后台启动限制。 */
        fun start(context: Context) {
            val i = Intent(context, NaviService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /** 结束导航、到达、或者退出导航页时收掉。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, NaviService::class.java))
        }
    }
}
