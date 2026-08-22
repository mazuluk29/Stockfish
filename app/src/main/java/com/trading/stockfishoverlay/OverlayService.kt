package com.trading.stockfishoverlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val ACTION_START_LIVE = "START_LIVE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var engine: StockfishEngine

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var overlay: LinearLayout? = null
    private var statusText: TextView? = null
    private var engineText: TextView? = null

    private var frameCounter = 0L
    private var lastUiUpdate = 0L

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                statusText?.post {
                    statusText?.text =
                        "LIVE • przechwytywanie zatrzymane"
                }

                releaseCapture(false)
            }
        }

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        engine = StockfishEngine(this)

        captureThread =
            HandlerThread("ScreenCaptureThread").apply {
                start()
            }

        captureHandler =
            Handler(captureThread!!.looper)

        createNotificationChannel()
        createOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForegroundNotification()

        if (intent?.action == ACTION_START_LIVE) {

            statusText?.text =
                "LIVE • otrzymano zgodę"

            val resultCode =
                intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    Activity.RESULT_CANCELED
                )

            val resultData: Intent? =
                if (Build.VERSION.SDK_INT >= 33) {

                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )

                } else {

                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA
                    )
                }

            if (
                resultCode == Activity.RESULT_OK &&
                resultData != null
            ) {

                startScreenCapture(
                    resultCode,
                    resultData
                )

            } else {

                statusText?.text =
                    "LIVE • brak zgody"
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            NotificationCompat.Builder(
                this,
                "stockfish_live"
            )
                .setSmallIcon(
                    android.R.drawable.ic_menu_search
                )
                .setContentTitle(
                    "Stockfish Overlay"
                )
                .setContentText(
                    "Przechwytywanie ekranu"
                )
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                100,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                100,
                notification
            )
        }
    }

    private fun startScreenCapture(
        resultCode: Int,
        resultData: Intent
    ) {

        releaseCapture(true)

        statusText?.post {
            statusText?.text =
                "LIVE • tworzę MediaProjection"
        }

        val projectionManager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val projection =
            projectionManager.getMediaProjection(
                resultCode,
                resultData
            )

        if (projection == null) {

            statusText?.post {
                statusText?.text =
                    "LIVE • MediaProjection = null"
            }

            return
        }

        mediaProjection = projection

        projection.registerCallback(
            projectionCallback,
            captureHandler
        )

        statusText?.post {
            statusText?.text =
                "LIVE • MediaProjection OK"
        }

        val metrics =
            DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
            .getRealMetrics(metrics)

        val width =
            metrics.widthPixels

        val height =
            metrics.heightPixels

        val density =
            metrics.densityDpi

        val reader =
            ImageReader.newInstance(
                width,
                height,
                Pixel
