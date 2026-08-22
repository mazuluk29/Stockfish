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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
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
        const val ACTION_START_LIVE =
            "START_LIVE"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"
    }

    private lateinit var wm:
        WindowManager

    private lateinit var engine:
        StockfishEngine

    private val tracker =
        BoardTracker()

    private val position =
        ChessPosition()

    private var projection:
        MediaProjection? = null

    private var imageReader:
        ImageReader? = null

    private var panel:
        LinearLayout? = null

    private var statusText:
        TextView? = null

    private var movesText:
        TextView? = null

    private var evalBar:
        EvalBarView? = null

    private var lastAnalysis =
        0L

    private val analysing =
        AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()

        engine =
            StockfishEngine(this)

        wm =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        createChannel()
        createOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForegroundNow()

        if (
            intent?.action ==
            ACTION_START_LIVE
        ) {

            val resultCode =
                intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    Activity.RESULT_CANCELED
                )

            val data =
                if (
                    Build.VERSION.SDK_INT >= 33
                ) {
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
                resultCode ==
                Activity.RESULT_OK &&
                data != null
            ) {
                startProjection(
                    resultCode,
                    data
                )
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNow() {

        val open =
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
                    "Analiza treningowa aktywna"
                )
                .setOngoing(true)
                .setContentIntent(open)
                .build()

        if (
            Build.VERSION.SDK_INT >= 29
        ) {
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

        projection?.stop()

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        projection =
            manager.getMediaProjection(
                resultCode,
                data
            )

        val metrics =
            DisplayMetrics()

        @Suppress("DEPRECATION")
        wm.defaultDisplay
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

        projection
            ?.createVirtualDisplay(
                "StockfishLive",
                width,
                height,
                density,
                DisplayManager
                    .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

        imageReader
            ?.setOnImageAvailableListener(
                { reader ->

                    val image =
                        reader.acquireLatestImage()
                            ?: return@setOnImageAvailableListener

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
                                pixelStride *
                                width

                        val bitmapWidth =
                            width +
                                rowPadding /
                                pixelStride

                        val bitmap =
                            Bitmap.createBitmap(
                                bitmapWidth,
                                height,
                                Bitmap.Config.ARGB_8888
                            )

                        bitmap.copyPixelsFromBuffer(
                            buffer
                        )

                        val cropped =
                            Bitmap.createBitmap(
                                bitmap,
                                0,
                                0,
                                width,
                                height
                            )

                        processFrame(cropped)

                        bitmap.recycle()

                    } finally {
                        image.close()
                    }

                },
                null
            )

        statusText?.text =
            "LIVE • szukam planszy"

        analyseCurrentPosition()
    }

    private fun processFrame(
        bitmap: Bitmap
    ) {

        val now =
            System.currentTimeMillis()

        if (
            now - lastAnalysis <
            700
        ) {
            bitmap.recycle()
            return
        }

        val changed =
            tracker.process(bitmap)

        bitmap.recycle()

        val area =
            tracker.getBoardArea()

        if (area != null) {

            evalBar?.let { bar ->

                val params =
                    bar.layoutParams
                        as WindowManager.LayoutParams

                params.x =
                    area.left

                params.y =
                    area.top

                params.height =
                    area.size

                wm.updateViewLayout(
                    bar,
                    params
                )
            }
        }

        if (
            changed == null ||
            changed.size < 2
        ) {
            return
        }

        val move =
            inferMove(
                changed
            ) ?: return

        if (
            position.applyMove(
                move
            )
        ) {

            statusText?.post {

                statusText?.text =
                    "LIVE • $move"
            }

            lastAnalysis = now

            analyseCurrentPosition()
        }
    }

    private fun inferMove(
        changed: List<String>
    ): String? {

        val own =
            changed.filter {
                position.isOwnPiece(it)
            }

        if (own.isEmpty())
            return null

        for (from in own) {

            for (to in changed) {

                if (from == to)
                    continue

                val piece =
                    position.pieceAt(from)

                if (
                    piece.uppercaseChar() ==
                    'P'
                ) {

                    val rank =
                        to[1]

                    if (
                        rank == '1' ||
                        rank == '8'
                    ) {
                        return "$from${to}q"
                    }
                }

                return "$from$to"
            }
        }

        return null
    }

    private fun analyseCurrentPosition() {

        if (
            analysing.getAndSet(true)
        ) return

        val fen =
            position.toFen()

        engine.analyzeFen(
            fen,
            12
        ) { result ->

            analysing.set(false)

            movesText?.post {

                if (
                    result.error != null
                ) {

                    movesText?.text =
                        result.error

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

                var eval =
                    result.evaluation
                        .toDoubleOrNull()

                /*
                 * Stockfish podaje ocenę
                 * z perspektywy strony na ruchu.
                 * Pasek pokazujemy z perspektywy białych.
                 */

                if (
                    eval != null &&
                    !position.whiteToMove
                ) {
                    eval = -eval
                }

                if (eval != null) {
                    evalBar
                        ?.setEvaluation(
                            eval
                        )
                }
            }
        }
    }

    private fun createOverlay() {

        val controls =
            LinearLayout(this)
                .apply {

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
            TextView(this)
                .apply {

                    text =
                        "LIVE • oczekiwanie"

                    textSize = 16f

                    setTextColor(
                        0xFFFFFFFF.toInt()
                    )
                }

        movesText =
            TextView(this)
                .apply {

                    text =
                        "Stockfish gotowy"

                    textSize = 15f

                    setTextColor(
                        0xFFFFFFFF.toInt()
                    )
                }

        controls.addView(
            statusText
        )

        controls.addView(
            movesText
        )

        val controlParams =
            WindowManager.LayoutParams(
                420,
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams
                        .FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

        controlParams.gravity =
            Gravity.TOP or
                Gravity.START

        controlParams.x = 20
        controlParams.y = 80

        panel = controls

        wm.addView(
            controls,
            controlParams
        )

        val bar =
            EvalBarView(this)

        val barParams =
            WindowManager.LayoutParams(
                18,
                400,
                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams
                        .FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )

        barParams.gravity =
            Gravity.TOP or
                Gravity.START

        barParams.x = 0
        barParams.y = 400

        evalBar = bar

        wm.addView(
            bar,
            barParams
        )
    }

    private fun createChannel() {

        if (
            Build.VERSION.SDK_INT >= 26
        ) {

            val channel =
                NotificationChannel(
                    "live",
                    "Stockfish LIVE",
                    NotificationManager
                        .IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(
                channel
            )
        }
    }

    override fun onDestroy() {

        imageReader?.close()
        imageReader = null

        projection?.stop()
        projection = null

        engine.shutdown()

        panel?.let {
            runCatching {
                wm.removeView(it)
            }
        }

        evalBar?.let {
            runCatching {
                wm.removeView(it)
            }
        }

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
