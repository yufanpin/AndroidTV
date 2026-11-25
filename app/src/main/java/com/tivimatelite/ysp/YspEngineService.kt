package com.tivimatelite.ysp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.tivimatelite.MainActivity
import com.tivimatelite.R
import com.tivimatelite.web.AppLogStore
import com.tivimatelite.web.FileLogStore

/** 引擎前台服务：保证直播代理在后台不被系统回收。 */
class YspEngineService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundSafely(buildNotification("正在启动引擎"))
        YspEngineService.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                stopEngine()
            }
        }
        YspEngine.start(this)
        startTicker()
        return START_STICKY
    }

    override fun onDestroy() {
        stopTicker()
        YspEngineService.running = false
        YspEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTicker() {
        stopTicker()
        val runnable = object : Runnable {
            override fun run() {
                YspEngine.refresh()
                updateNotification()
                handler.postDelayed(this, 2000L)
            }
        }
        ticker = runnable
        handler.post(runnable)
    }

    private fun stopTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    private fun stopEngine() {
        YspEngine.stop()
        AppLogStore.i(TAG, "引擎已手动停止")
        FileLogStore.i(TAG, "引擎已手动停止")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "直播引擎", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "本机直播代理运行状态"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val state = YspEngine.state.value
        val text = if (state.running) {
            "${state.channels} 频道 · 127.0.0.1:${state.port}"
        } else {
            "本地代理准备中"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(this, 0, intent, flags)
        return builder
            .setContentTitle("YSP TV · $status")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            startForeground(NOTIFICATION_ID, notification)
        }.onFailure {
            AppLogStore.w(TAG, "startForeground 失败", it)
        }
    }

    private fun updateNotification() {
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, buildNotification(if (YspEngine.state.value.running) "运行中" else "启动中"))
        }
    }

    companion object {
        private const val TAG = "YspEngineService"
        private const val CHANNEL_ID = "ysp_engine"
        private const val NOTIFICATION_ID = 0x5951
        const val ACTION_START = "com.tivimatelite.ysp.START_ENGINE"
        const val ACTION_STOP = "com.tivimatelite.ysp.STOP_ENGINE"
        const val ACTION_RESTART = "com.tivimatelite.ysp.RESTART_ENGINE"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) = send(context, ACTION_START)

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun restart(context: Context) = send(context, ACTION_RESTART)

        private fun send(context: Context, action: String) {
            val intent = Intent(context.applicationContext, YspEngineService::class.java)
                .setAction(action)
            runCatching {
                // 只有 START 需要 startForegroundService；STOP/RESTART 用 startService，
                // 避免「已 startForegroundService 但未 startForeground」触发系统 ANR。
                if (action == ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            }.onFailure {
                AppLogStore.w(TAG, "启动引擎服务失败: $action", it)
            }
        }
    }
}
