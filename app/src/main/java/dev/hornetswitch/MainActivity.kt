package dev.hornetswitch

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/** Amp LED colour of each slot: A blue, B green, C purple, D red. */
val SLOT_COLORS = intArrayOf(
    Color.parseColor("#3D7BFF"),
    Color.parseColor("#2ECC71"),
    Color.parseColor("#A55EEA"),
    Color.parseColor("#FF4D5E"),
)

/**
 * @param index global preset index
 * @return colour of the preset's slot
 */
fun slotColor(index: Int): Int = SLOT_COLORS[index % HornetProtocol.SLOT_COUNT]

class MainActivity : Activity(), HornetBleClient.Listener {
    private lateinit var settings: AppSettings
    private lateinit var client: HornetBleClient
    private lateinit var root: View
    private lateinit var glow: View
    private lateinit var setupText: TextView
    private lateinit var setupCounter: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var codeText: TextView
    private lateinit var labelText: TextView
    private lateinit var nameText: TextView
    private lateinit var progress: View
    private lateinit var nextCard: View
    private lateinit var nextDot: View
    private lateinit var nextName: TextView
    private lateinit var nextCode: TextView
    private lateinit var hintText: TextView

    private val glowDrawable = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        setGradientCenter(0.5f, 0.42f)
    }
    private var glowColor = COLOR_IDLE
    private var glowAnimator: ValueAnimator? = null

    private var detail: String? = null
    private var setupPosition = -1
    private var appliedSetup: Pair<Int, Setup>? = null
    private var setupChangePending = false
    private var permissionsBlocked = false
    private var lastTapAt = 0L

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> client.onBluetoothStateChanged(true)
                BluetoothAdapter.STATE_OFF -> client.onBluetoothStateChanged(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        glow = findViewById(R.id.glow)
        setupText = findViewById(R.id.setup)
        setupCounter = findViewById(R.id.setup_counter)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status)
        codeText = findViewById(R.id.code)
        labelText = findViewById(R.id.label)
        nameText = findViewById(R.id.name)
        progress = findViewById(R.id.progress)
        nextCard = findViewById(R.id.next_card)
        nextDot = findViewById(R.id.next_dot)
        nextName = findViewById(R.id.next_name)
        nextCode = findViewById(R.id.next_code)
        hintText = findViewById(R.id.hint)

        glow.background = glowDrawable
        glow.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            glowDrawable.gradientRadius = v.height * 0.6f
        }
        applyGlow(glowColor)

        settings = AppSettings(this)
        appliedSetup = settings.currentSetupIndex to settings.currentSetup
        client = HornetBleClient(this, settings, this)
        sharedClient = client

        root.setOnClickListener { onTap() }
        root.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        client.start()
        if (client.state == HornetState.PERMISSION_REQUIRED) requestBluetoothPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val state = client.state
        if (state == HornetState.PERMISSION_REQUIRED || state == HornetState.BLUETOOTH_OFF) client.start()
        if (settings.autoReconnect && (state == HornetState.NOT_FOUND || state == HornetState.DISCONNECTED)) client.start()
        val setup = settings.currentSetupIndex to settings.currentSetup
        if (setup != appliedSetup) {
            appliedSetup = setup
            setupChangePending = true
            applySetupChange()
        }
        render()
    }

    override fun onDestroy() {
        unregisterReceiver(bluetoothReceiver)
        if (sharedClient === client) sharedClient = null
        client.stop()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty()) return
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            permissionsBlocked = false
            client.start()
        } else {
            permissionsBlocked = permissions.none { shouldShowRequestPermissionRationale(it) }
            render()
        }
    }

    override fun onStateChanged(state: HornetState, detail: String?) {
        this.detail = detail
        if (state == HornetState.READY) applySetupChange()
        render()
    }

    /** After the setup was changed in settings, selects its first preset as soon as the amp is ready. */
    private fun applySetupChange() {
        if (!setupChangePending || client.state != HornetState.READY) return
        setupPosition = 0
        val first = settings.currentSetup.presets.first()
        if (client.activeIndex == first) {
            setupChangePending = false
        } else {
            client.selectPreset(first, SystemClock.uptimeMillis())
        }
    }

    override fun onActivePreset(index: Int, confirmedRequest: Boolean) {
        if (!confirmedRequest) setupPosition = -1
        if (confirmedRequest && settings.vibration) {
            getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        render()
        labelText.scaleX = 0.92f
        labelText.scaleY = 0.92f
        labelText.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator()).start()
    }

    override fun onChooseDevice(devices: List<BluetoothDevice>) {
        AlertDialog.Builder(this)
            .setTitle("Choose your Hornet")
            .setItems(devices.map { "${it.name ?: "?"}  ${it.address}" }.toTypedArray()) { _, which ->
                client.connectTo(devices[which])
            }
            .setCancelable(false)
            .show()
    }

    override fun onPresetNamesChanged() {
        render()
    }

    private fun onTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < TAP_DEBOUNCE_MS) return
        lastTapAt = now
        when (client.state) {
            HornetState.READY, HornetState.COMMAND_FAILED -> {
                setupChangePending = false
                setupPosition = nextPosition()
                client.selectPreset(settings.currentSetup.presets[setupPosition], now)
            }
            HornetState.PERMISSION_REQUIRED -> if (permissionsBlocked) openAppSettings() else requestBluetoothPermissions()
            HornetState.BLUETOOTH_OFF -> if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                requestBluetoothPermissions()
            }
            HornetState.NOT_FOUND, HornetState.DISCONNECTED, HornetState.RECONNECTING, HornetState.UNSUPPORTED_GATT -> client.start()
            else -> Unit
        }
    }

    /** Position of the active preset in the current setup; remembered so repeated presets cycle correctly. */
    private fun currentPosition(): Int {
        val presets = settings.currentSetup.presets
        if (setupPosition in presets.indices && presets[setupPosition] == client.activeIndex) return setupPosition
        return presets.indexOf(client.activeIndex)
    }

    private fun nextPosition(): Int = (currentPosition() + 1) % settings.currentSetup.presets.size

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun requestBluetoothPermissions() {
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    private fun render() {
        val state = client.state
        val connected = state == HornetState.READY || state == HornetState.SWITCHING || state == HornetState.COMMAND_FAILED
        val active = client.activeIndex
        setupText.text = settings.currentSetup.name
        setupCounter.text = "SETUP ${settings.currentSetupIndex + 1} OF ${settings.setups.size}"

        statusText.text = if (state == HornetState.READY) "CONNECTED" else state.label.uppercase()
        statusDot.background.mutate().setTint(
            when (state) {
                HornetState.READY -> COLOR_OK
                HornetState.COMMAND_FAILED, HornetState.NOT_FOUND, HornetState.UNSUPPORTED_GATT,
                HornetState.BLUETOOTH_OFF, HornetState.PERMISSION_REQUIRED, HornetState.DISCONNECTED -> COLOR_ERROR
                else -> COLOR_BUSY
            },
        )

        if (!connected) {
            animateGlow(COLOR_IDLE)
            codeText.visibility = View.GONE
            labelText.text = stateTitle(state)
            nameText.text = detail ?: ""
            progress.visibility = if (tapHint(state) == null) View.VISIBLE else View.GONE
            nextCard.visibility = View.INVISIBLE
            hintText.text = tapHint(state) ?: ""
            return
        }

        val setup = settings.currentSetup
        val position = currentPosition()
        progress.visibility = View.GONE
        nextCard.visibility = if (setup.presets.size > 1 || position < 0) View.VISIBLE else View.INVISIBLE
        if (active == null) {
            animateGlow(COLOR_IDLE)
            codeText.visibility = View.GONE
            labelText.text = "—"
            nameText.text = "Active preset unknown"
        } else {
            animateGlow(slotColor(active))
            codeText.visibility = View.VISIBLE
            codeText.text = HornetProtocol.presetName(active)
            codeText.background = GradientDrawable().apply {
                cornerRadius = 100f
                setStroke(3, slotColor(active))
            }
            labelText.text = settings.presetName(active) ?: HornetProtocol.presetName(active)
            nameText.text = if (position >= 0) "${position + 1} of ${setup.presets.size}" else "not in this setup"
        }

        val next = setup.presets[nextPosition()]
        nextDot.background.mutate().setTint(slotColor(next))
        nextName.text = settings.presetName(next) ?: HornetProtocol.presetName(next)
        nextCode.text = HornetProtocol.presetName(next)

        hintText.text = when (state) {
            HornetState.COMMAND_FAILED -> detail ?: state.label
            else -> "Tap to switch · hold for settings"
        }
    }

    private fun stateTitle(state: HornetState): String = when (state) {
        HornetState.BLUETOOTH_OFF -> "Bluetooth is off"
        HornetState.PERMISSION_REQUIRED -> "Permission needed"
        HornetState.SCANNING -> "Looking for Hornet"
        HornetState.CONNECTING -> "Connecting"
        HornetState.DISCOVERING, HornetState.INITIALIZING -> "Setting up"
        HornetState.RECONNECTING -> "Reconnecting"
        HornetState.NOT_FOUND -> "Hornet not found"
        HornetState.UNSUPPORTED_GATT -> "Unsupported device"
        else -> "Disconnected"
    }

    private fun tapHint(state: HornetState): String? = when (state) {
        HornetState.PERMISSION_REQUIRED -> if (permissionsBlocked) "Tap to open app settings and allow Nearby devices" else "Tap to grant permission"
        HornetState.BLUETOOTH_OFF -> "Tap to turn Bluetooth on"
        HornetState.NOT_FOUND, HornetState.DISCONNECTED, HornetState.UNSUPPORTED_GATT -> "Tap to reconnect"
        else -> null
    }

    private fun animateGlow(target: Int) {
        if (target == glowColor) return
        glowAnimator?.cancel()
        glowAnimator = ValueAnimator.ofArgb(glowColor, target).apply {
            duration = 350
            addUpdateListener {
                glowColor = it.animatedValue as Int
                applyGlow(glowColor)
            }
            start()
        }
    }

    private fun applyGlow(color: Int) {
        glowDrawable.colors = intArrayOf(
            Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)),
            Color.TRANSPARENT,
        )
    }

    companion object {
        /** Connection of the pedal screen, used by the setup editor to preview presets; null when not alive. */
        var sharedClient: HornetBleClient? = null
            private set

        private const val TAP_DEBOUNCE_MS = 300L
        private val COLOR_IDLE = Color.parseColor("#4A4F5C")
        private val COLOR_OK = Color.parseColor("#2ECC71")
        private val COLOR_BUSY = Color.parseColor("#FFB020")
        private val COLOR_ERROR = Color.parseColor("#FF4D5E")
    }
}
