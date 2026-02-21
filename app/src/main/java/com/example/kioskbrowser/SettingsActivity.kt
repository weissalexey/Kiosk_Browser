package com.example.kioskbrowser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Some providers don't support persistable permissions.
            }
            Prefs.setBarImageUri(this, uri.toString())
            findViewById<ImageView>(R.id.imgPreview).setImageURI(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val ed = findViewById<EditText>(R.id.edStartUrl)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnHome = findViewById<Button>(R.id.btnHome)
        val btnClose = findViewById<Button>(R.id.btnClose)
        val btnExit = findViewById<Button>(R.id.btnExitApp)

        val swBarEnabled = findViewById<SwitchCompat>(R.id.swBarEnabled)
        val swBarOverlay = findViewById<SwitchCompat>(R.id.swBarOverlay)
        val rbTop = findViewById<RadioButton>(R.id.rbTop)
        val rbBottom = findViewById<RadioButton>(R.id.rbBottom)
        val btnPickImage = findViewById<Button>(R.id.btnPickImage)
        val imgPreview = findViewById<ImageView>(R.id.imgPreview)
        val swBeepOnError = findViewById<SwitchCompat>(R.id.swBeepOnError)

        val seekZoom = findViewById<SeekBar>(R.id.seekZoom)
        val tvZoomValue = findViewById<TextView>(R.id.tvZoomValue)

        ed.setText(Prefs.getStartUrl(this))

        // Init bar settings
        swBarEnabled.isChecked = Prefs.isBarEnabled(this)
        swBarOverlay.isChecked = Prefs.isBarOverlay(this)
        if (Prefs.getBarPosition(this) == "bottom") rbBottom.isChecked = true else rbTop.isChecked = true

        // Sound
        swBeepOnError.isChecked = Prefs.isBeepOnError(this)

        // Zoom: 50..150 (SeekBar 0..100)
        val currentZoom = Prefs.getTextZoom(this)
        tvZoomValue.text = "${currentZoom}%"
        seekZoom.progress = (currentZoom - 50).coerceIn(0, 100)
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = (progress + 50).coerceIn(50, 150)
                tvZoomValue.text = "${percent}%"
                // Save immediately
                Prefs.setTextZoom(this@SettingsActivity, percent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val uriStr = Prefs.getBarImageUri(this)
        if (!uriStr.isNullOrBlank()) {
            try { imgPreview.setImageURI(Uri.parse(uriStr)) } catch (_: Exception) { /* ignore */ }
        }

        btnSave.setOnClickListener {
            val url = ed.text?.toString().orEmpty().trim()
            if (!UrlValidator.isValid(url)) {
                Toast.makeText(
                    this,
                    "Invalid URL. Example: http://SRV-WSS:7788/ or https://service.carstensen.eu/",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            Prefs.setStartUrl(this, url)
            Prefs.setBarEnabled(this, swBarEnabled.isChecked)
            Prefs.setBarPosition(this, if (rbBottom.isChecked) "bottom" else "top")
            Prefs.setBarOverlay(this, swBarOverlay.isChecked)
            Prefs.setBeepOnError(this, swBeepOnError.isChecked)
            // Prefs.setTextZoom saved live via SeekBar

            setResult(Activity.RESULT_OK)
            finish()
        }

        btnHome.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }

        btnExit.setOnClickListener {
            finishAffinity()
        }

        btnClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        btnPickImage.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/jpeg", "image/png", "image/*"))
        }
    }
}
