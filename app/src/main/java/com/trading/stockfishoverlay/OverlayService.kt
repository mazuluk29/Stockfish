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
        const val ACTION_START_LIVE = "START_LIVE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /*
         * Pełne rozpoznanie pozycji co 400 ms.
         *
         * Bez quickSignature, debounce,
         * stabilizacji i potwierdzania 2/2.
         */
        private const val FRAME_INTERVAL = 400L

        /*
         * Korekta pozycji strzałek.
         */
        private const val BOARD_Y_CORRECTION_SQUARES = 0.70f
    }

    private lateinit var windowManager: WindowManager
    private lateinit var engine: StockfishEngine

    private val recognizer =
        BoardRecognizer()

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val analysing =
        AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var infoOverlay: LinearLayout? = null
    private var boardOverlay: BoardOverlayView? = null

    private var statusText: TextView? = null
    private var analysisText: TextView? = null
    private var sideButton: Button? = null

    private var lastFrameTime = 0L

    /*
     * Ostatni poprawny układ figur,
     * który został wysłany do Stockfisha.
     */
    private var lastPlacement: String? = null

    /*
     * Ustawiane WYŁĄCZNIE przez użytkownika.
     *
     * true:
     * BIAŁE DÓŁ
     * Stockfish szuka ruchu białych.
     *
     * false:
     * CZARNE DÓŁ
     * Stockfish szuka ruchu czarnych.
     */
    private var whiteAtBottom = true

    private val projectionCallback =
        object : MediaProjection.Callback() {

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

        val resultData: Intent? =
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
            resultCode != Activity.RESULT_OK ||
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
                    android.R.drawable.ic_menu_search
                )
                .setContentTitle(
                    "Stockfish Overlay"
                )
                .setContentText(
                    "Analiza planszy aktywna"
                )
                .setOngoing(true)
                .build()

        if (
            Build.VERSION.SDK_INT >= 29
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

        /*
         * Jeżeli BoardRecognizer ma cache,
         * zerujemy go przy nowym przechwytywaniu.
         */
        runCatching {
            recognizer.reset()
        }

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

        val projectionManager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val newProjection =
            projectionManager
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
                        System.currentTimeMillis()

                    /*
                     * Ograniczenie do około
                     * 2,5 pełnego rozpoznania / sekundę.
                     */
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
                        error: Throwable
                    ) {

                        status(
                            "LIVE • ERROR: " +
                                (
                                    error.message
                                        ?: error
                                            .javaClass
                                            .simpleName
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

            /*
             * Nie ma już szybkich podpisów,
             * debounce ani stabilizacji.
             *
             * Po prostu co 400 ms próbujemy
             * odczytać aktualny FEN.
             */
            val result =
                recognizer
                    .recognize(
                        bitmap
                    )

            /*
             * Nie znaleziono prawidłowej planszy.
             */
            if (
                result == null
            ) {

                status(
                    "LIVE • szukam planszy..."
                )

                return
            }

            val placement =
                result.boardFen

            /*
             * Pokazujemy overlay już wtedy,
             * gdy sama plansza została znaleziona.
             */
            showBoardOverlay(
                result.area
            )

            /*
             * Rozpoznano planszę, ale układ figur
             * nie przechodzi podstawowej kontroli.
             *
             * Pokazujemy FEN diagnostycznie.
             */
            if (
                !isPlausiblePosition(
                    placement
                )
            ) {

                status(
                    "LIVE • rozpoznaję figury..."
                )

                mainHandler.post {

                    analysisText?.text =
                        "Odczyt FEN:\n" +
                            placement
                }

                return
            }

            /*
             * Pozycja jest taka sama jak poprzednio.
             * Niczego nie analizujemy ponownie.
             */
            if (
                placement ==
                lastPlacement
            ) {

                status(
                    if (
                        whiteAtBottom
                    ) {

                        "LIVE • gotowy • BIAŁE DÓŁ"

                    } else {

                        "LIVE • gotowy • CZARNE DÓŁ"
                    }
                )

                return
            }

            /*
             * NOWA POPRAWNA POZYCJA.
             */
            lastPlacement =
                placement

            status(
                "LIVE • nowa pozycja"
            )

            analysePosition(
                placement
            )

        } catch (
            error: Throwable
        ) {

            status(
                "LIVE • FRAME ERROR: " +
                    (
                        error.message
                            ?: error
                                .javaClass
                                .simpleName
                    )
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

        /*
         * Jeżeli poprzednia analiza jeszcze trwa,
         * nie odpalamy drugiego Stockfisha naraz.
         */
        if (
            analysing.getAndSet(
                true
            )
        ) {

            return
        }

        /*
         * Wybrany kolor określa:
         * - orientację planszy,
         * - stronę na ruchu.
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
                        "Analizuję...\n"
                    )

                    append(
                        "FEN:\n"
                    )

                    append(
                        fen
                    )
                }
        }

        engine
            .analyzeFen(
                fen,
                13
            ) { result ->

                analysing.set(
                    false
                )

                mainHandler.post {

                    if (
                        result.error != null
                    ) {

                        analysisText?.text =
                            buildString {

                                append(
                                    "BŁĄD:\n"
                                )

                                append(
                                    result.error
                                )

                                append(
                                    "\n\nFEN:\n"
                                )

                                append(
                                    fen
                                )
                            }

                        status(
                            "LIVE • błąd analizy"
                        )

                        return@post
                    }

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
                                "Ocena: "
                            )

                            append(
                                result.evaluation
                            )

                            append(
                                "\n"
                            )

                            result.moves
                                .take(5)
                                .forEachIndexed {
                                    index,
                                    move ->

                                    append(
                                        "${index + 1}. $move"
                                    )

                                    if (
                                        index < 4
                                    ) {

                                        append(
                                            "\n"
                                        )
                                    }
                                }
                        }

                    var evaluation =
                        result
                            .evaluation
                            .toDoubleOrNull()

                    /*
                     * Pasek oceny pokazujemy
                     * z perspektywy białych.
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
                            result.moves
                        )

                    status(
                        if (
                            whiteAtBottom
                        ) {

                            "LIVE • gotowy • BIAŁE DÓŁ"

                        } else {

                            "LIVE • gotowy • CZARNE DÓŁ"
                        }
                    )
                }
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

        var whiteKing = 0
        var blackKing = 0
        var pieces = 0

        for (
            row in rows
        ) {

            var squares = 0

            for (
                char in row
            ) {

                if (
                    char.isDigit()
                ) {

                    val amount =
                        char
                            .digitToInt()

                    if (
                        amount !in 1..8
                    ) {

                        return false
                    }

                    squares +=
                        amount

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

        /*
         * Musi być dokładnie jeden król
         * każdego koloru.
         */
        if (
            whiteKing != 1 ||
            blackKing != 1
        ) {

            return false
        }

        if (
            pieces !in 2..32
        ) {

            return false
        }

        return true
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

                view.whiteAtBottom =
                    whiteAtBottom

                /*
                 * Korekta Y o 0,7 pola do góry.
                 */
                val squareSize =
                    area.size /
                        8f

                val correction =
                    (
                        squareSize *
                            BOARD_Y_CORRECTION_SQUARES
                    ).toInt()

                val correctedTop =
                    area.top -
                        correction

                val currentParams =
                    view.layoutParams
                        as?
                        WindowManager
                            .LayoutParams

                if (
                    currentParams == null
                ) {

                    val params =
                        WindowManager
                            .LayoutParams(
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

                                PixelFormat
                                    .TRANSLUCENT
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
                error: Throwable
            ) {

                status(
                    "LIVE • OVERLAY ERROR: " +
                        (
                            error.message
                                ?: error
                                    .javaClass
                                    .simpleName
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

                /*
                 * Orientację zmienia tylko użytkownik.
                 */
                setOnClickListener {

                    whiteAtBottom =
                        !whiteAtBottom

                    updateSideButton()

                    boardOverlay
                        ?.whiteAtBottom =
                        whiteAtBottom

                    boardOverlay
                        ?.invalidate()

                    /*
                     * Po ręcznej zmianie koloru
                     * ponownie analizujemy tę samą
                     * pozycję dla drugiej strony.
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

        root.addView(
            statusText
        )

        root.addView(
            analysisText
        )

        root.addView(
            sideButton
        )

        val params =
            WindowManager
                .LayoutParams(
                    500,

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

                    PixelFormat
                        .TRANSLUCENT
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
        intent: Intent?
    ): IBinder? {

        return null
    }
}
