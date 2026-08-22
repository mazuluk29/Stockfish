package com.trading.stockfishoverlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode != Activity.RESULT_OK ||
                result.data == null
            ) {
                status.text = "Nie przyznano dostępu do ekranu"
                return@registerForActivityResult
            }

            val serviceIntent =
                Intent(
                    this,
                    OverlayService::class.java
                ).apply {
                    action = OverlayService.ACTION_START_LIVE

                    putExtra(
                        OverlayService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )

                    putExtra(
                        OverlayService.EXTRA_RESULT_DATA,
                        result.data
                    )
                }

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            status.text = "Analiza LIVE uruchomiona"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)

        findViewById<Button>(
            R.id.grantOverlay
        ).setOnClickListener {

            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<Button>(
            R.id.startLive
        ).setOnClickListener {

            if (!Settings.canDrawOverlays(this)) {
                status.text =
                    "Najpierw zezwól na nakładkę"
                return@setOnClickListener
            }

            val projectionManager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            screenCaptureLauncher.launch(
                projectionManager.createScreenCaptureIntent()
            )
        }

        findViewById<Button>(
            R.id.stopOverlay
        ).setOnClickListener {

            stopService(
                Intent(
                    this,
                    OverlayService::class.java
                )
            )

            status.text = "LIVE wyłączony"
        }
    }
}
