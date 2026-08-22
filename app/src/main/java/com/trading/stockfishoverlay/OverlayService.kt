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
import android.widget.Button
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

        private const val FRAME_INTERVAL =
            1200L

        /*
         * Korekta położenia strzałek.
         * Overlay przesuwamy o około 0.7 pola w górę.
         */
        private const val BOARD_Y_CORRECTION_SQUARES =
            0.70f
    }

    private lateinit var windowManager:
        WindowManager

    private lateinit var engine:
        StockfishEngine

    private val recognizer =
        BoardRecognizer()

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val analysing =
        AtomicBoolean(false)

    private var projection:
        MediaProjection? = null

    private var virtualDisplay:
        VirtualDisplay? = null

    private var imageReader:
        ImageReader? = null

    private var captureThread:
        HandlerThread? = null

    private var captureHandler:
        Handler? = null

    private var infoOverlay:
        LinearLayout? = null

    private var boardOverlay:
        BoardOverlayView? = null

    private var statusText:
        TextView? = null

    private var analysisText:
        TextView? = null

    private var sideButton:
        Button? = null

    private var refreshButton:
        Button? = null

    private var lastFrameTime =
        0L

    private var lastPlacement:
        String? = null

    /*
     * true  = BIAŁE DÓŁ
     * false = CZARNE DÓŁ
     *
     * Zmieniasz to tylko ręcznie.
     */
    private var whiteAtBottom =
        true

    private val projectionCallback =
        object :
            MediaProjection.Callback() {

            override fun onStop() {

                status(
                    "LIVE • przechwytywanie zatrzymane"
                )

                releaseCapture(
                    false
                )
            }
        }

    override fun onCreate() {

        super.onCreate()

        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        engine =
            StockfishEngine(
                this
            )

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

        val resultData:
            Intent? =

            if (
                Build.VERSION.SDK_INT >=
                33
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
            resultCode !=
            Activity.RESULT_OK ||
            resultData == null
        ) {

            status(
                "LIVE • brak zgody na ekran"
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
                    android.R.drawable
                        .ic_menu_search
                )
                .setContentTitle(
                    "Stockfish Overlay"
                )
                .setContentText(
                    "Analiza planszy aktywna"
                )
                .setOngoing(
                    true
                )
                .build()

        if (
            Build.VERSION.SDK_INT >=
            29
        ) {

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

        releaseCapture(
            true
        )

        lastPlacement =
            null

        lastFrameTime =
            0L

        analysing.set(
            false
        )

        mainHandler.post {

            analysisText?.text =
                "Czekam na planszę..."

            removeBoardOverlay()

            updateSideButton()
        }

        status(
            "LIVE • uruchamianie..."
        )

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val newProjection =
            manager
                .getMediaProjection(
                    resultCode,
                    data
                )

        if (
            newProjection == null
        ) {

            status(
                "LIVE • MediaProjection ERROR"
            )

            return
        }

        projection =
            newProjection

        newProjection
            .registerCallback(
                projectionCallback,
                captureHandler
            )

        val metrics =
            DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager
            .defaultDisplay
            .getRealMetrics(
                metrics
            )

        val width =
            metrics.widthPixels

        val height =
            metrics.heightPixels

        val density =
            metrics.densityDpi

        val reader =
            ImageReader
                .newInstance(
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

        if (
            virtualDisplay == null
        ) {

            status(
                "LIVE • VirtualDisplay ERROR"
            )

            return
        }

        status(
            "LIVE • szukam planszy..."
        )

        reader
            .setOnImageAvailableListener(
                { source ->

                    val image =
                        source
                            .acquireLatestImage()
                            ?: return@setOnImageAvailableListener

                    val now =
                        System
                            .currentTimeMillis()

                    if (
                        now -
                        lastFrameTime <
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
                                pixelStride *
                                width

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

                        padded
                            .copyPixelsFromBuffer(
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

                        processFrame(
                            screen
                        )

                    } catch (
                        error:
                            Throwable
                    ) {

                        status(
                            "LIVE • ERROR: " +
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

            val result =
                recognizer
                    .recognize(
                        bitmap
                    )

            if (
                result == null
            ) {

                status(
                    "LIVE • szukam planszy..."
                )

                return
            }

            if (
                !isPlausiblePosition(
                    result.boardFen
                )
            ) {

                status(
                    "LIVE • plansza znaleziona"
                )

                mainHandler.post {

                    analysisText?.text =
                        "Niepewne rozpoznanie figur:\n" +
                            result.boardFen
                }

                return
            }

            status(
                if (
                    whiteAtBottom
                ) {

                    "LIVE • plansza OK • BIAŁE DÓŁ"

                } else {

                    "LIVE • plansza OK • CZARNE DÓŁ"
                }
            )

            showBoardOverlay(
                result.area
            )

            /*
             * Pozycja bez zmian:
             * nie uruchamiamy kolejnej analizy.
             */
            if (
                lastPlacement ==
                result.boardFen
            ) {

                return
            }

            /*
             * Nie zmieniamy automatycznie koloru.
             */
            lastPlacement =
                result.boardFen

            analysePosition(
                result.boardFen
            )

        } finally {

            if (
                !bitmap.isRecycled
            ) {

                bitmap.recycle()
            }
        }
    }

    private fun analysePosition(
        placement: String
    ) {

        if (
            analysing
                .getAndSet(
                    true
                )
        ) {

            return
        }

        /*
         * BIAŁE DÓŁ:
         * szukamy najlepszych ruchów białych.
         *
         * CZARNE DÓŁ:
         * szukamy najlepszych ruchów czarnych.
         */
        val side =
            if (
                whiteAtBottom
            ) {

                "w"

            } else {

                "b"
            }

        val fen =
            "$placement $side - - 0 1"

        status(
            "LIVE • analizuję..."
        )

        mainHandler.post {

            analysisText?.text =
                buildString {

                    append(
                        if (
                            whiteAtBottom
                        ) {

                            "GRAM: BIAŁE\n"

                        } else {

                            "GRAM: CZARNE\n"
                        }
                    )

                    append(
                        "Stockfish analizuje..."
                    )
                }
        }

        engine.analyzeFen(
            fen,
            16
        ) { result ->

            analysing.set(
                false
            )

            mainHandler.post {

                if (
                    result.error != null
                ) {

                    analysisText?.text =
                        "BŁĄD:\n" +
                            result.error +
                            "\n\nFEN:\n" +
                            fen

                    status(
                        "LIVE • błąd analizy"
                    )

                    return@post
                }

                val classifiedMoves =
                    classifyMoves(
                        result.lines
                    )

                analysisText?.text =
                    buildString {

                        append(
                            if (
                                whiteAtBottom
                            ) {

                                "GRAM: BIAŁE\n"

                            } else {

                                "GRAM: CZARNE\n"
                            }
                        )

                        append(
                            "Ocena: ${result.evaluation}\n\n"
                        )

                        classifiedMoves
                            .take(5)
                            .forEachIndexed {
                                index,
                                move ->

                                append(
                                    "#${move.rank} "
                                )

                                append(
                                    move.move
                                )

                                append(
                                    "  "
                                )

                                append(
                                    move.evaluation
                                )

                                append(
                                    "  "
                                )

                                append(
                                    move.quality.label
                                )

                                if (
                                    index <
                                    classifiedMoves.size -
                                    1
                                ) {

                                    append(
                                        "\n"
                                    )
                                }
                            }
                    }

                var evaluation =
                    result.evaluation
                        .toDoubleOrNull()

                /*
                 * Pasek ewaluacji z perspektywy białych.
                 */
                if (
                    evaluation != null &&
                    !whiteAtBottom
                ) {

                    evaluation =
                        -evaluation
                }

                boardOverlay
                    ?.whiteAtBottom =
                    whiteAtBottom

                boardOverlay
                    ?.update(
                        evaluation,
                        classifiedMoves
                    )

                status(
                    "LIVE • analiza gotowa"
                )
            }
        }
    }

    /*
     * Klasyfikacja propozycji ruchów.
     *
     * Nie jest to algorytm Chess.com 1:1,
     * ale daje podobną interpretację jakości
     * na podstawie różnicy względem najlepszego ruchu.
     */
    private fun classifyMoves(
        lines:
            List<EngineLine>
    ): List<OverlayMove> {

        if (
            lines.isEmpty()
        ) {

            return emptyList()
        }

        fun score(
            line:
                EngineLine
        ): Int {

            line.centipawns
                ?.let {

                    return it
                }

            line.mate
                ?.let {

                    return if (
                        it > 0
                    ) {

                        100000 -
                            it * 100

                    } else {

                        -100000 -
                            it * 100
                    }
                }

            return -1000000
        }

        val bestLine =
            lines.first()

        val bestScore =
            score(
                bestLine
            )

        return lines
            .take(5)
            .map {
                line ->

                val currentScore =
                    score(
                        line
                    )

                val loss =
                    (
                        bestScore -
                            currentScore
                        )
                        .coerceAtLeast(
                            0
                        )

                val quality =
                    when {

                        /*
                         * Najlepszy ruch.
                         */
                        line.rank == 1 -> {

                            MoveQuality.BEST
                        }

                        /*
                         * Prawie identyczny
                         * z najlepszym.
                         */
                        loss <= 15 -> {

                            MoveQuality.EXCELLENT
                        }

                        loss <= 40 -> {

                            MoveQuality.GOOD
                        }

                        loss <= 90 -> {

                            MoveQuality.INACCURACY
                        }

                        loss <= 180 -> {

                            MoveQuality.MISTAKE
                        }

                        else -> {

                            MoveQuality.BLUNDER
                        }
                    }

                OverlayMove(
                    rank =
                        line.rank,

                    move =
                        line.move,

                    evaluation =
                        line.evaluation,

                    quality =
                        quality
                )
            }
    }

    private fun isPlausiblePosition(
        placement: String
    ): Boolean {

        val rows =
            placement
                .split(
                    "/"
                )

        if (
            rows.size != 8
        ) {

            return false
        }

        var whiteKing =
            0

        var blackKing =
            0

        var pieces =
            0

        for (
            row in rows
        ) {

            var squares =
                0

            for (
                char in row
            ) {

                if (
                    char.isDigit()
                ) {

                    squares +=
                        char
                            .digitToInt()

                    continue
                }

                squares++

                when (
                    char
                ) {

                    'K' -> {

                        whiteKing++
                        pieces++
                    }

                    'k' -> {

                        blackKing++
                        pieces++
                    }

                    'Q',
                    'R',
                    'B',
                    'N',
                    'P',
                    'q',
                    'r',
                    'b',
                    'n',
                    'p' -> {

                        pieces++
                    }

                    else -> {

                        return false
                    }
                }
            }

            if (
                squares != 8
            ) {

                return false
            }
        }

        return (
            whiteKing == 1 &&
                blackKing == 1 &&
                pieces in 2..32
            )
    }

    private fun showBoardOverlay(
        area:
            BoardRecognizer.BoardArea
    ) {

        mainHandler.post {

            try {

                var view =
                    boardOverlay

                if (
                    view == null
                ) {

                    view =
                        BoardOverlayView(
                            this
                        )

                    boardOverlay =
                        view
                }

                /*
                 * Orientacja ręczna.
                 */
                view.whiteAtBottom =
                    whiteAtBottom

                val squareSize =
                    area.size /
                        8f

                val correction =
                    (
                        squareSize *
                            BOARD_Y_CORRECTION_SQUARES
                        )
                        .toInt()

                val correctedTop =
                    area.top -
                        correction

                val currentParams =
                    view.layoutParams
                        as?
                        WindowManager.LayoutParams

                if (
                    currentParams == null
                ) {

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
                        correctedTop

                    windowManager
                        .addView(
                            view,
                            params
                        )

                } else {

                    currentParams.x =
                        area.left

                    currentParams.y =
                        correctedTop

                    currentParams.width =
                        area.size

                    currentParams.height =
                        area.size

                    windowManager
                        .updateViewLayout(
                            view,
                            currentParams
                        )
                }

            } catch (
                error:
                    Throwable
            ) {

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

    private fun removeBoardOverlay() {

        val view =
            boardOverlay
                ?: return

        runCatching {

            windowManager
                .removeView(
                    view
                )
        }

        boardOverlay =
            null
    }

    private fun createInfoOverlay() {

        val root =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    14,
                    8,
                    14,
                    8
                )

                setBackgroundColor(
                    0xC0181818.toInt()
                )
            }

        statusText =
            TextView(
                this
            ).apply {

                text =
                    "LIVE • oczekiwanie"

                textSize =
                    14f

                setTextColor(
                    0xFFFFFFFF.toInt()
                )
            }

        analysisText =
            TextView(
                this
            ).apply {

                text =
                    "Czekam na planszę..."

                textSize =
                    12f

                setTextColor(
                    0xFFFFFFFF.toInt()
                )
            }

        sideButton =
            Button(
                this
            ).apply {

                text =
                    "BIAŁE DÓŁ"

                textSize =
                    11f

                setOnClickListener {

                    whiteAtBottom =
                        !whiteAtBottom

                    updateSideButton()

                    /*
                     * Obracamy obecne strzałki.
                     */
                    boardOverlay
                        ?.whiteAtBottom =
                        whiteAtBottom

                    boardOverlay
                        ?.invalidate()

                    /*
                     * Zmieniamy stronę na ruchu
                     * i ponownie analizujemy
                     * aktualną pozycję.
                     */
                    val placement =
                        lastPlacement

                    if (
                        placement != null &&
                        !analysing.get()
                    ) {

                        analysePosition(
                            placement
                        )
                    }
                }
            }

        refreshButton =
            Button(
                this
            ).apply {

                text =
                    "ODŚWIEŻ"

                textSize =
                    11f

                setOnClickListener {

                    /*
                     * Dzięki temu kolejna klatka
                     * zostanie potraktowana jako
                     * nowa pozycja, nawet jeśli
                     * FEN się nie zmienił.
                     */
                    lastPlacement =
                        null

                    status(
                        "LIVE • odświeżam..."
                    )

                    analysisText?.text =
                        "Ponowne rozpoznawanie planszy..."
                }
            }

        root.addView(
            statusText
        )

        root.addView(
            analysisText
        )

        root.addView(
            sideButton
        )

        root.addView(
            refreshButton
        )

        val params =
            WindowManager.LayoutParams(
                470,

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
                        .FLAG_NOT_TOUCH_MODAL,

                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or
                Gravity.START

        params.x =
            20

        params.y =
            75

        infoOverlay =
            root

        windowManager
            .addView(
                root,
                params
            )
    }

    private fun updateSideButton() {

        mainHandler.post {

            sideButton?.text =
                if (
                    whiteAtBottom
                ) {

                    "BIAŁE DÓŁ"

                } else {

                    "CZARNE DÓŁ"
                }

            statusText?.text =
                if (
                    whiteAtBottom
                ) {

                    "LIVE • ustawiono BIAŁE DÓŁ"

                } else {

                    "LIVE • ustawiono CZARNE DÓŁ"
                }
        }
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
        stopProjection:
            Boolean
    ) {

        runCatching {

            imageReader
                ?.setOnImageAvailableListener(
                    null,
                    null
                )
        }

        runCatching {

            imageReader
                ?.close()
        }

        imageReader =
            null

        runCatching {

            virtualDisplay
                ?.release()
        }

        virtualDisplay =
            null

        val oldProjection =
            projection

        projection =
            null

        if (
            oldProjection != null
        ) {

            runCatching {

                oldProjection
                    .unregisterCallback(
                        projectionCallback
                    )
            }

            if (
                stopProjection
            ) {

                runCatching {

                    oldProjection
                        .stop()
                }
            }
        }
    }

    override fun onDestroy() {

        releaseCapture(
            true
        )

        runCatching {

            engine
                .shutdown()
        }

        mainHandler.post {

            removeBoardOverlay()

            infoOverlay
                ?.let {

                    runCatching {

                        windowManager
                            .removeView(
                                it
                            )
                    }
                }

            infoOverlay =
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
        intent:
            Intent?
    ): IBinder? {

        return null
    }
}
