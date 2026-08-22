package com.trading.stockfishoverlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var overlayView: LinearLayout? = null
    private var statusText: TextView? = null
    private var stockfishText: TextView? = null

    private var frameCount = 0L
    private var lastStatusUpdate = 0L

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                status("LIVE • udostępnianie zatrzymane")
                clearCapture(false)
            }
        }

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        engine = StockfishEngine(this)

        captureThread =
            HandlerThread("StockfishCapture").apply {
                start()
            }

        captureHandler =
            Handler(captureThread!!.looper)

        createChannel()
        createOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForegroundNotification()

        if (intent?.action != ACTION_START_LIVE) {
            return START_NOT_STICKY
        }

        status("LIVE • zgoda przekazana")

        val resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

        val resultData =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(
                    EXTRA_RESULT_DATA
                )
            }

        if (
            resultCode != Activity.RESULT_OK ||
            resultData == null
        ) {
            status("LIVE • brak zgody na ekran")
            return START_NOT_STICKY
        }

        startCapture(
            resultCode,
            resultData
        )

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {

        val notification =
            NotificationCompat.Builder(
                this,
                "stockfish_live"
            )
                .setSmallIcon(
                    android.R.drawable.ic_menu_search
                )
                .setContentTitle("Stockfish Overlay")
                .setContentText("Analiza ekranu aktywna")
                .setOngoing(true)
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

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {

        clearCapture(true)

        status("LIVE • tworzę MediaProjection")

        val projectionManager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val newProjection =
            projectionManager.getMediaProjection(
                resultCode,
                data
            )

        if (newProjection == null) {
            status("LIVE • MediaProjection ERROR")
            return
        }

        projection = newProjection

        newProjection.registerCallback(
            projectionCallback,
            captureHandler
        )

        status("LIVE • MediaProjection OK")

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
            .getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader =
            ImageReader.newInstance(
                width,
                height,
                android.graphics.PixelFormat.RGBA_8888,
                2
            )

        imageReader = reader

        val display =
            newProjection.createVirtualDisplay(
                "StockfishCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler
            )

        if (display == null) {
            status("LIVE • VirtualDisplay ERROR")
            return
        }

        virtualDisplay = display

        status("LIVE • VirtualDisplay OK")

        reader.setOnImageAvailableListener(
            { source ->

                val image =
                    source.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                try {
                    frameCount++

                    val now =
                        System.currentTimeMillis()

                    if (now - lastStatusUpdate >= 1000L) {
                        lastStatusUpdate = now

                        status(
                            "LIVE • klatki OK: $frameCount"
                        )
                    }
                } finally {
                    image.close()
                }
            },
            captureHandler
        )
    }

    private fun createOverlay() {

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL

                setPadding(
                    18,
                    12,
                    18,
                    12
                )

                setBackgroundColor(
                    0xCC181818.toInt()
                )
            }

        statusText =
            TextView(this).apply {
                text = "LIVE • oczekiwanie"
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
            }

        stockfishText =
            TextView(this).apply {
                text =
                    if (engine.isAvailable()) {
                        "Stockfish gotowy"
                    } else {
                        "Brak Stockfisha"
                    }

                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
            }

        root.addView(statusText)
        root.addView(stockfishText)

        val overlayType =
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params =
            WindowManager.LayoutParams(
                470,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or Gravity.START

        params.x = 20
        params.y = 80

        overlayView = root

        windowManager.addView(
            root,
            params
        )
    }

    private fun createChannel() {

        if (Build.VERSION.SDK_INT < 26) {
            return
        }

        val channel =
            NotificationChannel(
                "stockfish_live",
                "Stockfish LIVE",
                NotificationManager.IMPORTANCE_LOW
            )

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(
            channel
        )
    }

    private fun status(text: String) {
        statusText?.post {
            statusText?.text = text
        }
    }

    private fun clearCapture(
        stopProjection: Boolean
    ) {

        runCatching {
            imageReader?.setOnImageAvailableListener(
                null,
                null
            )
        }

        runCatching {
            imageReader?.close()
        }

        imageReader = null

        runCatching {
            virtualDisplay?.release()
        }

        virtualDisplay = null

        val oldProjection = projection
        projection = null

        if (oldProjection != null) {

            runCatching {
                oldProjection.unregisterCallback(
                    projectionCallback
                )
            }

            if (stopProjection) {
                runCatching {
                    oldProjection.stop()
                }
            }
        }
    }

    override fun onDestroy() {

        clearCapture(true)

        runCatching {
            engine.shutdown()
        }

        overlayView?.let { view ->
            runCatching {
                windowManager.removeView(view)
            }
        }

        overlayView = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}
