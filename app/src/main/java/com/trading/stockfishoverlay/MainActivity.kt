package com.trading.stockfishoverlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)

        findViewById<Button>(R.id.grantOverlay).setOnClickListener {

            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<Button>(R.id.startOverlay).setOnClickListener {

            if (!Settings.canDrawOverlays(this)) {

                status.text =
                    "Najpierw zezwól na wyświetlanie nad innymi aplikacjami."

                return@setOnClickListener
            }

            ContextCompat.startForegroundService(
                this,
                Intent(this, OverlayService::class.java)
            )

            status.text = "Overlay uruchomiony"
        }

        findViewById<Button>(R.id.stopOverlay).setOnClickListener {

            stopService(
                Intent(this, OverlayService::class.java)
            )

            status.text = "Overlay wyłączony"
        }
    }
}
