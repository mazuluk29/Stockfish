package com.trading.stockfishoverlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    companion object {
        const val ACTION_START_LIVE = "START_LIVE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CAPTURE_INTERVAL = 700L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var engine: StockfishEngine

    private val tracker = BoardTracker()
    private val position = ChessPosition()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var panel: LinearLayout? = null
    private var statusText: TextView? = null
    private var movesText: TextView? = null
    private var evalBar: EvalBarView? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var lastCapturedFrame = 0L
    private var lastMoveTime = 0L

    private val analysing = AtomicBoolean(false)

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                super.onStop()

                statusText?.post {
                    statusText?.text =
                        "LIVE • przechwytywanie zatrzymane"
                }

                releaseCaptureResources(
                    stopProjection = false
                )
            }
        }

    override fun onCreate() {
        super.onCreate()

        engine = StockfishEngine(this)

        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        captureThread =
            HandlerThread(
                "StockfishCapture"
            ).apply {
                start()
            }

        captureHandler =
            Handler(
                captureThread!!.looper
            )

        createChannel()
        createOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForegroundNow()

        if (intent?.action == ACTION_START_LIVE) {

            statusText?.text =
                "LIVE • zgoda odebrana"

            val resultCode =
                intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    Activity.RESULT_CANCELED
                )

            val data: Intent? =
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
                data != null
            ) {

                startProjection(
                    resultCode,
                    data
                )

            } else {

                statusText?.text =
                    "LIVE • brak zgody na ekran"
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNow() {

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
                "live"
            )
                .setSmallIcon(
                    android.R.drawable.ic_menu_search
                )
                .setContentTitle(
                    "Stockfish LIVE"
                )
                .setContentText(
                    "Analiza ekranu aktywna"
                )
                .setOngoing(true)
                .setContentIntent(
                    pendingIntent
                )
                .build()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                77,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                77,
                notification
            )
        }
    }

    private fun startProjection(
        resultCode: Int,
        data: Intent
    ) {

        releaseCaptureResources(
            stopProjection = true
        )

        statusText?.post {
            statusText?.text =
                "LIVE • tworzę MediaProjection"
        }

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val projection =
            manager.getMediaProjection(
                resultCode,
                data
            )

        if (projection == null) {

            statusText?.post {
                statusText?.text =
                    "LIVE • błąd MediaProjection"
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
        windowManager
            .defaultDisplay
            .getRealMetrics(metrics)

        val width =
            metrics.widthPixels

        val height =
            metrics.heightPixels

        val density =
            metrics.densityDpi

        imageReader =
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

        val reader =
            imageReader

        if (reader == null) {

            statusText?.post {
                statusText?.text =
                    "LIVE • błąd ImageReader"
            }

            return
        }

        virtualDisplay =
            projection.createVirtualDisplay(
                "StockfishLive",
                width,
                height,
                density,
                DisplayManager
                    .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler
            )

        if (virtualDisplay == null) {

            statusText?.post {
                statusText?.text =
                    "LIVE • błąd VirtualDisplay"
            }

            return
        }

        statusText?.post {
            statusText?.text =
                "LIVE • VirtualDisplay OK"
        }

        reader.setOnImageAvailableListener(
            { imageReader ->

                val image =
                    imageReader.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                val now =
                    System.currentTimeMillis()

                if (
                    now - lastCapturedFrame <
                    CAPTURE_INTERVAL
                ) {

                    image.close()
                    return@setOnImageAvailableListener
                }

                lastCapturedFrame = now

                try {

                    val plane =
                        image.planes[0]

                    val buffer =
                        plane.buffer

                    val pixelStride =
                        plane.pixelStride

                    val rowStride =
                        plane.rowStride

                    val rowPadding =
                        rowStride -
                            pixelStride * width

                    val bitmapWidth =
                        width +
                            rowPadding /
                            pixelStride

                    val fullBitmap =
                        Bitmap.createBitmap(
                            bitmapWidth,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
