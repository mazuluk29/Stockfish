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

        // Jak często patrzymy na ekran.
        private const val FRAME_INTERVAL = 180L

        // Jak duża zmiana obrazu oznacza ruch.
        private const val CHANGE_THRESHOLD = 2.8

        // Po ruchu czekamy chwilę, aż animacja się skończy.
        private const val DEBOUNCE_MS = 300L

        // Jeśli rozpoznanie FEN się nie uda,
        // ponawiamy próbę po takim czasie.
        private const val RETRY_MS = 250L

        // Korekta położenia strzałek.
        private const val BOARD_Y_CORRECTION_SQUARES = 0.70f
    }

    private lateinit var windowManager: WindowManager
    private lateinit var engine: StockfishEngine

    private val recognizer = BoardRecognizer()

    private val mainHandler =
        Handler(Looper.getMainLooper())

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
     * Ostatnia pozycja faktycznie wysłana
     * do Stockfisha.
     */
    private var lastPlacement: String? = null

    /*
     * Lekki podpis poprzedniej klatki.
     */
    private var previousSignature: LongArray? = null

    /*
     * Kiedy ostatnio wykryliśmy zmianę obrazu.
     */
    private var lastBoardChangeTime = 0L

    /*
     * Czy czekamy na rozpoznanie nowej pozycji.
     */
    private var positionPending = true

    /*
     * Ostatnia próba pełnego rozpoznania.
     */
    private var lastRecognitionAttempt = 0L

    /*
     * TYLKO użytkownik zmienia tę wartość.
     *
     * true:
     * BIAŁE DÓŁ
     * Stockfish analizuje ruch białych.
     *
     * false:
     * CZARNE DÓŁ
     * Stockfish analizuje ruch czarnych.
     */
    private var whiteAtBottom = true

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

        releaseCapture(true)

        recognizer.reset()

        lastFrameTime = 0L
        lastPlacement = null

        previousSignature = null

        lastBoardChangeTime =
            System.currentTimeMillis()

        positionPending = true

        lastRecognitionAttempt = 0L

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
            manager.getMediaProjection(
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
            newProjection.createVirtualDisplay(
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

            val now =
                System.currentTimeMillis()

            /*
             * Bardzo szybkie sprawdzenie obrazu.
             */
            val signature =
                recognizer.quickSignature(
                    bitmap
                )

            /*
             * Nie znaleziono planszy.
             */
            if (
                signature == null
            ) {

                previousSignature = null

                positionPending = true

                status(
                    "LIVE • szukam planszy..."
                )

                return
            }

            val previous =
                previousSignature

            previousSignature =
                signature

            /*
             * Pierwsza klatka z planszą.
             */
            if (
                previous == null
            ) {

                positionPending = true

                lastBoardChangeTime =
                    now - DEBOUNCE_MS

                tryRecognizePosition(
                    bitmap,
                    now
                )

                return
            }

            /*
             * Jak bardzo zmieniła się plansza.
             */
            val difference =
                recognizer.signatureDifference(
                    previous,
                    signature
                )

            /*
             * Wykryliśmy zmianę.
             *
             * Resetujemy timer debounce.
             */
            if (
                difference >=
                CHANGE_THRESHOLD
            ) {

                lastBoardChangeTime =
                    now

                positionPending =
                    true

                status(
                    "LIVE • wykryto zmianę"
                )

                return
            }

            /*
             * Nic się już nie zmienia.
             *
             * Jeżeli wcześniej wykryliśmy ruch
             * i minęło 300 ms, rozpoznajemy
             * pełną pozycję.
             */
            if (
                positionPending &&
                now -
                    lastBoardChangeTime >=
                DEBOUNCE_MS
            ) {

                tryRecognizePosition(
                    bitmap,
                    now
                )

                return
            }

            /*
             * Normalny stan oczekiwania.
             */
            if (
                !positionPending &&
                lastPlacement != null
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
            }

        } finally {

            if (
                !bitmap.isRecycled
            ) {

                bitmap.recycle()
            }
        }
    }

    private fun tryRecognizePosition(
        bitmap: Bitmap,
        now: Long
    ) {

        /*
         * Jeżeli poprzednia próba była błędna,
         * nie wykonujemy następnej co 1 ms.
         */
        if (
            now -
                lastRecognitionAttempt <
            RETRY_MS
        ) {

            return
        }

        lastRecognitionAttempt =
            now

        status(
            "LIVE • rozpoznaję pozycję..."
        )

        val result =
            recognizer.recognize(
                bitmap
            )

        /*
         * Plansza chwilowo źle rozpoznana.
         *
         * Zostawiamy positionPending = true,
         * więc spróbujemy ponownie.
         */
        if (
            result == null
        ) {

            status(
                "LIVE • ponawiam rozpoznanie..."
            )

            return
        }

        if (
            !isPlausiblePosition(
                result.boardFen
            )
        ) {

            status(
                "LIVE • ponawiam figury..."
            )

            return
        }

        /*
         * To dokładnie ta sama pozycja.
         *
         * Nie odpalamy Stockfisha drugi raz.
         */
        if (
            result.boardFen ==
            lastPlacement
        ) {

            positionPending =
                false

            showBoardOverlay(
                result.area
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

            return
        }

        /*
         * Mamy NOWĄ poprawną pozycję.
         *
         * Od razu ją zatwierdzamy.
         */
        lastPlacement =
            result.boardFen

        positionPending =
            false

        showBoardOverlay(
            result.area
        )

        analysePosition(
            result.boardFen
        )
    }

    private fun analysePosition(
        placement: String
    ) {

        if (
            analysing.getAndSet(
                true
            )
        ) {

            return
        }

        /*
         * Użytkownik wybiera stronę.
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
                if (
                    whiteAtBottom
                ) {

                    "GRAM: BIAŁE\nAnalizuję..."

                } else {

                    "GRAM: CZARNE\nAnalizuję..."
                }
        }

        engine.analyzeFen(
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
                        "BŁĄD:\n" +
                            result.error

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

                                if (
                                    index < 4
                                ) {

                                    append("\n")
                                }
                            }
                    }

                var evaluation =
                    result.evaluation
                        .toDoubleOrNull()

                if (
                    evaluation != null &&
                    !whiteAtBottom
                ) {

                    evaluation =
                        -evaluation
                }

                boardOverlay?.whiteAtBottom =
                    whiteAtBottom

                boardOverlay?.update(
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
            placement.split("/")

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

                    squares +=
                        char.digitToInt()

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
        area: BoardRecognizer.BoardArea
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

                val params =
                    view.layoutParams
                        as?
                        WindowManager.LayoutParams

                if (
                    params == null
                ) {

                    val newParams =
                        WindowManager.LayoutParams(
                            area.size,
                            area.size,
                            WindowManager.LayoutParams
                                .TYPE_APPLICATION_OVERLAY,

                            WindowManager.LayoutParams
                                .FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams
                                    .FLAG_NOT_TOUCHABLE,

                            PixelFormat.TRANSLUCENT
                        )

                    newParams.gravity =
                        Gravity.TOP or
                            Gravity.START

                    newParams.x =
                        area.left

                    newParams.y =
                        correctedTop

                    windowManager.addView(
                        view,
                        newParams
                    )

                } else {

                    params.x =
                        area.left

                    params.y =
                        correctedTop

                    params.width =
                        area.size

                    params.height =
                        area.size

                    windowManager.updateViewLayout(
                        view,
                        params
                    )
                }

            } catch (
                error: Throwable
            ) {

                status(
                    "LIVE • OVERLAY ERROR"
                )
            }
        }
    }

    private fun removeBoardOverlay() {

        val view =
            boardOverlay
                ?: return

        runCatching {

            windowManager.removeView(
                view
            )
        }

        boardOverlay =
            null
    }

    private fun createInfoOverlay() {

        val root =
            LinearLayout(this).apply {

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
            TextView(this).apply {

                text =
                    "LIVE • oczekiwanie"

                textSize =
                    14f

                setTextColor(
                    0xFFFFFFFF.toInt()
                )
            }

        analysisText =
            TextView(this).apply {

                text =
                    "Czekam na planszę..."

                textSize =
                    12f

                setTextColor(
                    0xFFFFFFFF.toInt()
                )
            }

        sideButton =
            Button(this).apply {

                text =
                    "BIAŁE DÓŁ"

                textSize =
                    11f

                /*
                 * Tylko użytkownik zmienia orientację.
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
            WindowManager.LayoutParams(
                430,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,

                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,

                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or
                Gravity.START

        params.x = 20
        params.y = 75

        infoOverlay =
            root

        windowManager.addView(
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
                NotificationManager.IMPORTANCE_LOW
            )

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(
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

        imageReader = null

        runCatching {
            virtualDisplay?.release()
        }

        virtualDisplay = null

        val old =
            projection

        projection = null

        if (
            old != null
        ) {

            runCatching {

                old.unregisterCallback(
                    projectionCallback
                )
            }

            if (
                stopProjection
            ) {

                runCatching {
                    old.stop()
                }
            }
        }
    }

    override fun onDestroy() {

        releaseCapture(true)

        runCatching {
            engine.shutdown()
        }

        mainHandler.post {

            removeBoardOverlay()

            infoOverlay?.let {

                runCatching {

                    windowManager.removeView(
                        it
                    )
                }
            }

            infoOverlay = null
        }

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
