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

                releaseCaptureResources()
            }
        }

    override fun onCreate() {
        super.onCreate()

        engine = StockfishEngine(this)

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

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

        startForegroundNow()

        if (intent?.action == ACTION_START_LIVE) {

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

        val openApp =
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
                .setContentIntent(openApp)
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

        releaseCaptureResources()

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val projection =
            manager.getMediaProjection(
                resultCode,
                data
            )

        mediaProjection = projection

        projection.registerCallback(
            projectionCallback,
            captureHandler
        )

        val metrics =
            DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
            .getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader =
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

        virtualDisplay =
            projection.createVirtualDisplay(
                "StockfishLive",
                width,
                height,
                density,
                DisplayManager
                    .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                captureHandler
            )

        imageReader!!
            .setOnImageAvailableListener(
                { reader ->

                    val image =
                        reader.acquireLatestImage()
                            ?: return@setOnImageAvailableListener

                    val now =
                        System.currentTimeMillis()

                    /*
                     * Bardzo ważne:
                     * nie tworzymy Bitmapy dla każdej
                     * klatki ekranu.
                     */
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

                        fullBitmap
                            .copyPixelsFromBuffer(buffer)

                        val screenBitmap =
                            Bitmap.createBitmap(
                                fullBitmap,
                                0,
                                0,
                                width,
                                height
                            )

                        fullBitmap.recycle()

                        processFrame(
                            screenBitmap
                        )

                    } catch (e: Exception) {

                        statusText?.post {
                            statusText?.text =
                                "LIVE • błąd: ${e.message}"
                        }

                    } finally {

                        image.close()
                    }

                },
                captureHandler
            )

        tracker.reset()
        position.reset()

        statusText?.post {
            statusText?.text =
                "LIVE • szukam planszy..."
        }

        analyseCurrentPosition()
    }

    private fun processFrame(
        bitmap: Bitmap
    ) {

        try {

            val changed =
                tracker.process(bitmap)

            val area =
                tracker.getBoardArea()

            if (area != null) {

                statusText?.post {

                    if (
                        changed == null ||
                        changed.isEmpty()
                    ) {

                        statusText?.text =
                            "LIVE • plansza wykryta"
                    }
                }

                evalBar?.post {

                    val bar =
                        evalBar
                            ?: return@post

                    val params =
                        bar.layoutParams
                            as? WindowManager.LayoutParams
                            ?: return@post

                    params.x = area.left
                    params.y = area.top
                    params.height = area.size

                    try {

                        windowManager
                            .updateViewLayout(
                                bar,
                                params
                            )

                    } catch (_: Exception) {
                    }
                }
            }

            if (
                changed == null ||
                changed.size < 2
            ) {
                return
            }

            val now =
                System.currentTimeMillis()

            /*
             * Ochrona przed wykryciem tej samej
             * animacji ruchu kilka razy.
             */
            if (
                now - lastMoveTime <
                1000
            ) {
                return
            }

            val move =
                inferMove(changed)
                    ?: return

            if (
                position.applyMove(move)
            ) {

                lastMoveTime = now

                statusText?.post {
                    statusText?.text =
                        "LIVE • $move"
                }

                analyseCurrentPosition()
            }

        } finally {

            bitmap.recycle()
        }
    }

    private fun inferMove(
        changed: List<String>
    ): String? {

        val ownSquares =
            changed.filter {
                position.isOwnPiece(it)
            }

        if (ownSquares.isEmpty()) {
            return null
        }

        for (from in ownSquares) {

            for (to in changed) {

                if (from == to) {
                    continue
                }

                val piece =
                    position.pieceAt(from)

                if (
                    piece.uppercaseChar() == 'P'
                ) {

                    val rank = to[1]

                    if (
                        rank == '1' ||
                        rank == '8'
                    ) {

                        return "${from}${to}q"
                    }
                }

                return "$from$to"
            }
        }

        return null
    }

    private fun analyseCurrentPosition() {

        if (analysing.getAndSet(true)) {
            return
        }

        val fen =
            position.toFen()

        engine.analyzeFen(
            fen,
            12
        ) { result ->

            analysing.set(false)

            movesText?.post {

                if (result.error != null) {

                    movesText?.text =
                        "Stockfish: ${result.error}"

                    return@post
                }

                movesText?.text =
                    buildString {

                        append(
                            "Ocena: ${result.evaluation}\n"
                        )

                        result.moves
                            .take(5)
                            .forEachIndexed {
                                    index,
                                    move ->

                                append(
                                    "${index + 1}. $move\n"
                                )
                            }
                    }

                var value =
                    result.evaluation
                        .toDoubleOrNull()

                if (
                    value != null &&
                    !position.whiteToMove
                ) {

                    value = -value
                }

                if (value != null) {

                    evalBar
                        ?.setEvaluation(value)
                }
            }
        }
    }

    private fun createOverlay() {

        val controls =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

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

                text =
                    "LIVE • oczekiwanie"

                textSize = 16f

                setTextColor(
                    0xFFFFFFFF.toInt()
                )
            }

        movesText =
            TextView(this).apply {

                text =
                    "Stockfish gotowy"

                textSize = 15f

                setTextColor(
                    0xFFFFFFFF.toInt()
                )
            }

        controls.addView(statusText)
        controls.addView(movesText)

        val type =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY

            } else {

                @Suppress("DEPRECATION")
                WindowManager.LayoutParams
                    .TYPE_PHONE
            }

        val controlParams =
            WindowManager.LayoutParams(
                420,
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                type,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams
                        .FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

        controlParams.gravity =
            Gravity.TOP or Gravity.START

        controlParams.x = 20
        controlParams.y = 80

        panel = controls

        windowManager.addView(
            controls,
            controlParams
        )

        val bar =
            EvalBarView(this)

        val barParams =
            WindowManager.LayoutParams(
                22,
                400,
                type,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams
                        .FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )

        barParams.gravity =
            Gravity.TOP or Gravity.START

        barParams.x = 0
        barParams.y = 400

        evalBar = bar

        windowManager.addView(
            bar,
            barParams
        )
    }

    private fun createChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel =
                NotificationChannel(
                    "live",
                    "Stockfish LIVE",
                    NotificationManager
                        .IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            )
                .createNotificationChannel(
                    channel
                )
        }
    }

    private fun releaseCaptureResources() {

        try {
            imageReader
                ?.setOnImageAvailableListener(
                    null,
                    null
                )
        } catch (_: Exception) {
        }

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }

        imageReader = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        mediaProjection?.let { projection ->

            try {
                projection.unregisterCallback(
                    projectionCallback
                )
            } catch (_: Exception) {
            }

            try {
                projection.stop()
            } catch (_: Exception) {
            }
        }

        mediaProjection = null
    }

    override fun onDestroy() {

        releaseCaptureResources()

        engine.shutdown()

        panel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        evalBar?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        captureThread
            ?.quitSafely()

        captureThread = null
        captureHandler = null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
