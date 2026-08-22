package com.trading.stockfishoverlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.Looper
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

        private const val FRAME_INTERVAL = 700L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var engine: StockfishEngine

    private val tracker = BoardTracker()
    private val position = ChessPosition()

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var infoOverlay: LinearLayout? = null
    private var boardOverlay: BoardOverlayView? = null

    private var statusText: TextView? = null
    private var analysisText: TextView? = null

    private var lastFrameTime = 0L
    private var lastMoveTime = 0L

    private var boardWasLocked = false

    private val analysing =
        AtomicBoolean(false)

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                status(
                    "LIVE • przechwytywanie zatrzymane"
                )

                releaseCapture(false)
            }
        }

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        engine =
            StockfishEngine(this)

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
        createInfoOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startNotification()

        if (
            intent?.action !=
            ACTION_START_LIVE
        ) {
            return START_NOT_STICKY
        }

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

            status(
                "LIVE • brak zgody"
            )

            return START_NOT_STICKY
        }

        startCapture(
            resultCode,
            resultData
        )

        return START_NOT_STICKY
    }

    private fun startNotification() {

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
                    "Analiza treningowa aktywna"
                )
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                100,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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

        releaseCapture(true)

        tracker.reset()
        position.reset()

        boardWasLocked = false

        lastFrameTime = 0L
        lastMoveTime = 0L

        mainHandler.post {

            analysisText?.text =
                "Czekam na planszę..."

            boardOverlay?.let {

                runCatching {
                    windowManager.removeView(it)
                }
            }

            boardOverlay = null
        }

        status(
            "LIVE • uruchamianie"
        )

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val newProjection =
            manager.getMediaProjection(
                resultCode,
                data
            )

        if (newProjection == null) {

            status(
                "LIVE • MediaProjection ERROR"
            )

            return
        }

        projection =
            newProjection

        newProjection.registerCallback(
            projectionCallback,
            captureHandler
        )

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

        val reader =
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

        imageReader =
            reader

        virtualDisplay =
            newProjection
                .createVirtualDisplay(
                    "StockfishCapture",
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

            status(
                "LIVE • VirtualDisplay ERROR"
            )

            return
        }

        status(
            "LIVE • szukam planszy..."
        )

        reader.setOnImageAvailableListener(
            { source ->

                val image =
                    source.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                val now =
                    System.currentTimeMillis()

                if (
                    now - lastFrameTime <
                    FRAME_INTERVAL
                ) {

                    image.close()
                    return@setOnImageAvailableListener
                }

                lastFrameTime =
                    now

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

                    val padded =
                        Bitmap.createBitmap(
                            bitmapWidth,
                            height,
                            Bitmap.Config.ARGB_8888
                        )

                    padded.copyPixelsFromBuffer(
                        buffer
                    )

                    val screen =
                        Bitmap.createBitmap(
                            padded,
                            0,
                            0,
                            width,
                            height
                        )

                    padded.recycle()

                    try {

                        processFrame(
                            screen
                        )

                    } catch (error: Throwable) {

                        status(
                            "LIVE • FRAME ERROR: " +
                                (
                                    error.message
                                        ?: error.javaClass.simpleName
                                )
                        )
                    }

                } catch (error: Throwable) {

                    status(
                        "LIVE • CAPTURE ERROR: " +
                            (
                                error.message
                                    ?: error.javaClass.simpleName
                            )
                    )

                } finally {

                    image.close()
                }
            },
            captureHandler
        )
    }

    private fun processFrame(
        bitmap: Bitmap
    ) {

        try {

            val changed =
                tracker.process(
                    bitmap
                )

            val area =
                tracker.boardArea()

            /*
             * BRAK PRAWDZIWEJ PLANSZY.
             */
            if (
                area == null ||
                !tracker.isLocked
            ) {

                if (boardWasLocked) {

                    boardWasLocked =
                        false

                    position.reset()

                    analysing.set(false)

                    mainHandler.post {

                        analysisText?.text =
                            "Czekam na planszę..."

                        boardOverlay?.let {

                            runCatching {
                                windowManager
                                    .removeView(it)
                            }
                        }

                        boardOverlay =
                            null
                    }
                }

                status(
                    "LIVE • szukam planszy..."
                )

                return
            }

            /*
             * WŁAŚNIE WYKRYTO STABILNĄ PLANSZĘ.
             */
            if (!boardWasLocked) {

                boardWasLocked =
                    true

                position.reset()

                lastMoveTime =
                    System.currentTimeMillis()

                status(
                    "LIVE • plansza zablokowana"
                )

                showBoardOverlayOnMain(
                    area
                )

                analysePosition()

                return
            }

            showBoardOverlayOnMain(
                area
            )

            status(
                "LIVE • plansza OK"
            )

            if (
                changed == null ||
                changed.size < 2
            ) {
                return
            }

            val now =
                System.currentTimeMillis()

            if (
                now - lastMoveTime <
                1000L
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

                lastMoveTime =
                    now

                status(
                    "LIVE • ruch $move"
                )

                analysePosition()
            }

        } finally {

            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun inferMove(
        changed:
            List<BoardTracker.ChangedSquare>
    ): String? {

        val sources =
            changed.filter {

                position.isOwnPiece(
                    it.square
                )
            }

        if (sources.isEmpty()) {
            return null
        }

        data class Candidate(
            val move: String,
            val score: Double
        )

        val candidates =
            mutableListOf<Candidate>()

        for (source in sources) {

            for (destination in changed) {

                if (
                    source.square ==
                    destination.square
                ) {
                    continue
                }

                if (
                    !position.isPseudoLegal(
                        source.square,
                        destination.square
                    )
                ) {
                    continue
                }

                var move =
                    source.square +
                        destination.square

                val piece =
                    position.pieceAt(
                        source.square
                    )

                if (
                    piece.uppercaseChar() == 'P' &&
                    (
                        destination.square[1] == '1' ||
                        destination.square[1] == '8'
                    )
                ) {

                    move += "q"
                }

                candidates +=
                    Candidate(
                        move,
                        source.difference +
                            destination.difference
                    )
            }
        }

        return candidates
            .maxByOrNull {
                it.score
            }
            ?.move
    }

    private fun analysePosition() {

        if (!boardWasLocked) {
            return
        }

        if (
            analysing.getAndSet(true)
        ) {
            return
        }

        val fen =
            position.toFen()

        engine.analyzeFen(
            fen,
            13
        ) { result ->

            analysing.set(false)

            if (!boardWasLocked) {
                return@analyzeFen
            }

            mainHandler.post {

                if (!boardWasLocked) {
                    return@post
                }

                if (
                    result.error != null
                ) {

                    analysisText?.text =
                        result.error

                    return@post
                }

                analysisText?.text =
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
                                    "${index + 1}. $move"
                                )

                                if (index < 4) {
                                    append("\n")
                                }
                            }
                    }

                var evaluation =
                    result.evaluation
                        .toDoubleOrNull()

                if (
                    evaluation != null &&
                    !position.whiteToMove
                ) {

                    evaluation =
                        -evaluation
                }

                boardOverlay?.update(
                    evaluation,
                    result.moves
                )
            }
        }
    }

    private fun showBoardOverlayOnMain(
        area:
            BoardTracker.BoardArea
    ) {

        mainHandler.post {

            if (!boardWasLocked) {
                return@post
            }

            try {

                val existing =
                    boardOverlay

                if (existing == null) {

                    val view =
                        BoardOverlayView(
                            this
                        )

                    view.whiteAtBottom =
                        tracker.whiteAtBottom

                    val params =
                        WindowManager.LayoutParams(
                            area.size,
                            area.size,

                            WindowManager
                                .LayoutParams
                                .TYPE_APPLICATION_OVERLAY,

                            WindowManager
                                .LayoutParams
                                .FLAG_NOT_FOCUSABLE or
                                WindowManager
                                    .LayoutParams
                                    .FLAG_NOT_TOUCHABLE,

                            PixelFormat.TRANSLUCENT
                        )

                    params.gravity =
                        Gravity.TOP or
                            Gravity.START

                    params.x =
                        area.left

                    params.y =
                        area.top

                    boardOverlay =
                        view

                    windowManager.addView(
                        view,
                        params
                    )

                } else {

                    val params =
                        existing.layoutParams
                            as? WindowManager.LayoutParams
                            ?: return@post

                    params.x =
                        area.left

                    params.y =
                        area.top

                    params.width =
                        area.size

                    params.height =
                        area.size

                    windowManager
                        .updateViewLayout(
                            existing,
                            params
                        )
                }

            } catch (error: Throwable) {

                status(
                    "LIVE • OVERLAY ERROR: " +
                        (
                            error.message
                                ?: error.javaClass.simpleName
                        )
                )
            }
        }
    }

    private fun createInfoOverlay() {

        val root =
            LinearLayout(this)
                .apply {

                    orientation =
                        LinearLayout.VERTICAL

                    setPadding(
                        14,
                        8,
                        14,
                        8
                    )

                    setBackgroundColor(
                        0xB8181818.toInt()
                    )
                }

        statusText =
            TextView(this)
                .apply {

                    text =
                        "LIVE • oczekiwanie"

                    textSize =
                        14f

                    setTextColor(
                        0xFFFFFFFF.toInt()
                    )
                }

        analysisText =
            TextView(this)
                .apply {

                    text =
                        "Czekam na planszę..."

                    textSize =
                        13f

                    setTextColor(
                        0xFFFFFFFF.toInt()
                    )
                }

        root.addView(
            statusText
        )

        root.addView(
            analysisText
        )

        val params =
            WindowManager.LayoutParams(
                360,
                WindowManager
                    .LayoutParams
                    .WRAP_CONTENT,

                WindowManager
                    .LayoutParams
                    .TYPE_APPLICATION_OVERLAY,

                WindowManager
                    .LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager
                        .LayoutParams
                        .FLAG_NOT_TOUCHABLE,

                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or
                Gravity.START

        params.x =
            22

        params.y =
            80

        infoOverlay =
            root

        windowManager.addView(
            root,
            params
        )
    }

    private fun status(
        text: String
    ) {

        mainHandler.post {

            statusText?.text =
                text
        }
    }

    private fun createChannel() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        val channel =
            NotificationChannel(
                "stockfish_live",
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

    private fun releaseCapture(
        stopProjection: Boolean
    ) {

        runCatching {

            imageReader
                ?.setOnImageAvailableListener(
                    null,
                    null
                )
        }

        runCatching {
            imageReader?.close()
        }

        imageReader =
            null

        runCatching {
            virtualDisplay?.release()
        }

        virtualDisplay =
            null

        val old =
            projection

        projection =
            null

        if (old != null) {

            runCatching {

                old.unregisterCallback(
                    projectionCallback
                )
            }

            if (stopProjection) {

                runCatching {
                    old.stop()
                }
            }
        }
    }

    override fun onDestroy() {

        boardWasLocked =
            false

        releaseCapture(true)

        runCatching {
            engine.shutdown()
        }

        mainHandler.post {

            infoOverlay?.let {

                runCatching {
                    windowManager
                        .removeView(it)
                }
            }

            boardOverlay?.let {

                runCatching {
                    windowManager
                        .removeView(it)
                }
            }

            infoOverlay =
                null

            boardOverlay =
                null
        }

        captureThread
            ?.quitSafely()

        captureThread =
            null

        captureHandler =
            null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
