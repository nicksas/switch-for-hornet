package dev.hornetswitch

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var setupsList: LinearLayout
    private lateinit var vibration: Switch
    private lateinit var keepScreenOn: Switch
    private lateinit var autoReconnect: Switch
    private lateinit var deviceText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)

        findViewById<TextView>(R.id.names_hint).text = if (settings.presetName(0) == null) {
            "Preset names appear after the first connection to the Hornet"
        } else {
            "Preset names are read from the amp"
        }
        setupsList = findViewById(R.id.setups)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.about).text = "Switch for Hornet v$version · by nicksas"
        vibration = findViewById<Switch>(R.id.vibration).apply { isChecked = settings.vibration }
        keepScreenOn = findViewById<Switch>(R.id.keep_screen_on).apply { isChecked = settings.keepScreenOn }
        autoReconnect = findViewById<Switch>(R.id.auto_reconnect).apply { isChecked = settings.autoReconnect }
        deviceText = findViewById(R.id.device)
        renderDevice()

        findViewById<Button>(R.id.add_setup).setOnClickListener {
            val setups = settings.setups
            settings.setups = setups + Setup("Setup ${setups.size + 1}", listOf(settings.currentSetup.presets.first()))
            settings.currentSetupIndex = setups.size
            openSetup(setups.size)
        }
        findViewById<Button>(R.id.forget_device).setOnClickListener {
            val client = MainActivity.sharedClient
            if (client != null) {
                client.forgetDevice()
            } else {
                settings.deviceAddress = null
                settings.deviceName = null
                settings.clearPresetNames()
            }
            renderDevice()
            renderSetups()
            Toast.makeText(this, "Amp forgotten — the nearest Hornet will be used", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.export_log).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "hornet-switch-log.txt")
                .putExtra(Intent.EXTRA_TEXT, DiagnosticLog.text().takeLast(MAX_EXPORT_CHARS))
            startActivity(Intent.createChooser(send, "Export diagnostics"))
        }
    }

    override fun onResume() {
        super.onResume()
        renderSetups()
        renderDevice()
    }

    override fun onPause() {
        super.onPause()
        settings.vibration = vibration.isChecked
        settings.keepScreenOn = keepScreenOn.isChecked
        settings.autoReconnect = autoReconnect.isChecked
    }

    private fun renderSetups() {
        setupsList.removeAllViews()
        val current = settings.currentSetupIndex
        settings.setups.forEachIndexed { index, setup ->
            val row = layoutInflater.inflate(R.layout.item_setup, setupsList, false)
            row.findViewById<View>(R.id.selected).background.mutate()
                .setTint(if (index == current) ACCENT else Color.TRANSPARENT)
            row.findViewById<TextView>(R.id.setup_name).text = setup.name
            row.findViewById<TextView>(R.id.setup_presets).text = setup.presets.joinToString("  →  ") { preset ->
                val name = settings.presetName(preset)
                if (name == null) HornetProtocol.presetName(preset) else "${HornetProtocol.presetName(preset)} $name"
            }
            row.setOnClickListener {
                settings.currentSetupIndex = index
                renderSetups()
            }
            row.findViewById<View>(R.id.edit).setOnClickListener { openSetup(index) }
            setupsList.addView(row)
        }
    }

    private fun openSetup(index: Int) {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_INDEX, index))
    }

    private fun renderDevice() {
        val address = settings.deviceAddress
        deviceText.text = if (address == null) {
            "Not selected — the nearest Hornet will be used"
        } else {
            "${settings.deviceName ?: "Hornet"}\n$address"
        }
    }

    private companion object {
        val ACCENT = Color.parseColor("#5CD888")

        /** Keeps the shared text well below the Binder transaction limit. */
        const val MAX_EXPORT_CHARS = 200_000
    }
}
