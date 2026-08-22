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

        private const val FRAME_INTERVAL = 1200L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var engine: StockfishEngine

    private val recognizer = BoardRecognizer()

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
    private var sideButton: Button? = null

    private var lastFrameTime = 0L

    private var lastPlacement: String? = null

    /*
     * true  = białe na ruchu
     * false = czarne na ruchu
     *
     * Po każdym rozpoznanym nowym ustawieniu
     * automatycznie zmieniamy stronę.
     *
     * Przycisk w overlayu pozwala to poprawić
     * ręcznie w dowolnym momencie.
     */
    private var whiteToMove = true

    private var currentWhiteAtBottom = true

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

        lastPlacement = null
        lastFrameTime = 0L

        mainHandler.post {

            analysisText?.text =
                "Czekam na planszę..."

            removeBoardOverlay()
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

                    processFrame(
                        screen
                    )

                } catch (error: Throwable) {

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
                recognizer.recognize(
                    bitmap
                )

            /*
             * Nic przypominającego prawidłową
             * szachownicę nie znaleziono.
             */
            if (result == null) {

                status(
                    "LIVE • szukam planszy..."
                )

                lastPlacement =
                    null

                mainHandler.post {

                    analysisText?.text =
                        "Czekam na planszę..."

                    removeBoardOverlay()
                }

                return
            }

            /*
             * Dodatkowa kontrola poprawności FEN.
             */
            if (
                !isPlausiblePosition(
                    result.boardFen
                )
            ) {

                status(
                    "LIVE • plansza znaleziona, rozpoznaję figury..."
                )

                return
            }

            currentWhiteAtBottom =
                result.whiteAtBottom

            status(
                if (result.whiteAtBottom) {
                    "LIVE • plansza OK • białe na dole"
                } else {
                    "LIVE • plansza OK • czarne na dole"
                }
            )

            showBoardOverlay(
                result.area,
                result.whiteAtBottom
            )

            val previous =
                lastPlacement

            /*
             * Pozycja nie zmieniła się.
             * Nie ma sensu ponownie odpalać Stockfisha.
             */
            if (
                previous ==
                result.boardFen
            ) {
                return
            }

            /*
             * Jeżeli mieliśmy poprzednią pozycję,
             * oznacza to najczęściej wykonanie ruchu.
             */
            if (previous != null) {
                whiteToMove =
                    !whiteToMove

                updateSideButton()
            }

            lastPlacement =
                result.boardFen

            analysePosition(
                result.boardFen
            )

        } finally {

            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun analysePosition(
        placement: String
    ) {

        if (
            analysing.getAndSet(true)
        ) {
            return
        }

        /*
         * Z samego obrazu nie możemy niezawodnie
         * ustalić praw do roszady ani en passant.
         *
         * Dlatego używamy:
         *
         * - - 0 1
         *
         * Dla ogromnej większości pozycji
         * analiza będzie poprawna.
         */
        val fen =
            buildString {

                append(placement)

                if (whiteToMove) {
                    append(" w ")
                } else {
                    append(" b ")
                }

                append("- - 0 1")
            }

        status(
            "LIVE • analizuję..."
        )

        engine.analyzeFen(
            fen,
            14
        ) { result ->

            analysing.set(false)

            mainHandler.post {

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
                            if (whiteToMove) {
                                "Ruch: BIAŁE\n"
                            } else {
                                "Ruch: CZARNE\n"
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

                                if (index < 4) {
                                    append("\n")
                                }
                            }
                    }

                var evaluation =
                    result.evaluation
                        .toDoubleOrNull()

                /*
                 * Pasek pokazujemy zawsze
                 * z perspektywy białych.
                 */
                if (
                    evaluation != null &&
                    !whiteToMove
                ) {
                    evaluation =
                        -evaluation
                }

                boardOverlay?.update(
                    evaluation,
                    result.moves
                )

                status(
                    "LIVE • analiza gotowa
