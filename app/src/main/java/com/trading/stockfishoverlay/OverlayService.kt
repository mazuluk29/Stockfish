package com.trading.stockfishoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var engine: StockfishEngine

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForegroundServiceNotification()

        engine = StockfishEngine(this)

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        showOverlay()
    }

    private fun startForegroundServiceNotification() {

        val openAppIntent = Intent(
            this,
            MainActivity::class.java
        )

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification =
            NotificationCompat.Builder(this, "overlay")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Stockfish Overlay")
                .setContentText("Nakładka jest aktywna")
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

        startForeground(77, notification)
    }

    private fun showOverlay() {

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                20,
                16,
                20,
                16
            )

            setBackgroundColor(
                Color.argb(
                    220,
                    25,
                    25,
                    25
                )
            )
        }

        val header = TextView(this).apply {
            text = "♟ Stockfish Overlay"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(10, 10, 10, 15)
        }

        val fenInput = EditText(this).apply {
            hint = "Wklej pozycję FEN"

            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)

            setText(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            )
        }

        val analyzeButton = Button(this).apply {
            text = "ANALIZUJ"
        }

        val resultText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f

            text =
                if (engine.isAvailable()) {
                    "Stockfish gotowy"
                } else {
                    "Brak libstockfish.so"
                }
        }

        val evalBar = EvalBarView(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    35,
                    250
                )
        }

        val closeButton = Button(this).apply {
            text = "ZAMKNIJ"

            setOnClickListener {
                stopSelf()
            }
        }

        analyzeButton.setOnClickListener {

            val fen =
                fenInput.text
                    .toString()
                    .trim()

            if (fen.isEmpty()) {
                resultText.text = "Wpisz pozycję FEN"
                return@setOnClickListener
            }

            resultText.text = "Analizowanie..."

            engine.analyzeFen(
                fen = fen,
                depth = 14
            ) { result ->

                panel.post {

                    if (result.error != null) {

                        resultText.text =
                            result.error

                        return@post
                    }

                    val text = buildString {

                        append(
                            "Ocena: ${result.evaluation}\n\n"
                        )

                        result.moves
                            .take(10)
                            .forEachIndexed { index, move ->

                                append(
                                    "${index + 1}. $move\n"
                                )
                            }
                    }

                    resultText.text = text

                    result.evaluation
                        .toDoubleOrNull()
                        ?.let { evaluation ->

                            evalBar.setEvaluation(
                                evaluation
                            )
                        }
                }
            }
        }

        panel.addView(header)
        panel.addView(fenInput)
        panel.addView(analyzeButton)
        panel.addView(resultText)
        panel.addView(evalBar)
        panel.addView(closeButton)

        val windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params =
            WindowManager.LayoutParams(
                650,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or Gravity.START

        params.x = 30
        params.y = 180

        var initialX = 0
        var initialY = 0

        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    initialX = params.x
                    initialY = params.y

                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    params.x =
                        initialX +
                        (event.rawX - initialTouchX)
                            .toInt()

                    params.y =
                        initialY +
                        (event.rawY - initialTouchY)
                            .toInt()

                    windowManager.updateViewLayout(
                        panel,
                        params
                    )

                    true
                }

                else -> false
            }
        }

        overlayView = panel

        windowManager.addView(
            panel,
            params
        )
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    "overlay",
                    "Stockfish Overlay",
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
    }

    override fun onDestroy() {

        overlayView?.let { view ->

            runCatching {
                windowManager.removeView(view)
            }
        }

        engine.shutdown()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
