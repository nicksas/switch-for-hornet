package dev.hornetswitch

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

class SetupActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var nameEdit: EditText
    private lateinit var presetsList: LinearLayout
    private lateinit var presetItems: List<String>

    private var index = 0
    private val presets = mutableListOf<Int>()
    private var deleted = false
    private var openRow: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var previewTarget: Int? = null
    private val previewRetry = Runnable { sendPreview() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        settings = AppSettings(this)
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, settings.setups.lastIndex)
        val setup = settings.setups[index]

        presetItems = (0 until HornetProtocol.PRESET_COUNT).map { preset ->
            val name = settings.presetName(preset)
            if (name == null) HornetProtocol.presetName(preset) else "${HornetProtocol.presetName(preset)}  ·  $name"
        }
        presets.addAll(setup.presets)

        nameEdit = findViewById<EditText>(R.id.name).apply { setText(setup.name) }
        presetsList = findViewById(R.id.presets)
        renderPresets()

        findViewById<Button>(R.id.add_preset).setOnClickListener { pickPreset(presets.size) }
        findViewById<Button>(R.id.delete_setup).apply {
            visibility = if (settings.setups.size > 1) View.VISIBLE else View.GONE
            setOnClickListener { confirmDelete() }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(previewRetry)
        if (deleted) return
        val name = nameEdit.text.toString().trim().ifBlank { "Setup ${index + 1}" }
        settings.setups = settings.setups.toMutableList().also { it[index] = Setup(name, presets.toList()) }
    }

    private fun renderPresets() {
        openRow = null
        presetsList.removeAllViews()
        presets.forEachIndexed { position, preset ->
            val row = layoutInflater.inflate(R.layout.item_setup_preset, presetsList, false)
            row.findViewById<View>(R.id.dot).background.mutate().setTint(slotColor(preset))
            row.findViewById<TextView>(R.id.preset).text = presetItems[preset]
            attachReorder(row, row.findViewById(R.id.handle))
            val content = row.findViewById<View>(R.id.content)
            content.setOnClickListener { pickPreset(position) }
            val delete = row.findViewById<View>(R.id.delete)
            delete.visibility = if (presets.size > 1) View.VISIBLE else View.INVISIBLE
            delete.setOnClickListener {
                openRow = null
                presets.removeAt(position)
                renderPresets()
            }
            attachSwipeToReveal(content)
            presetsList.addView(row)
        }
    }

    /**
     * Opens the preset sheet; each tap switches the amp so the preset can be heard before confirming.
     * @param position slot in the setup; presets.size appends a new preset
     */
    private fun pickPreset(position: Int) {
        var choice = presets.getOrElse(position) { presets.last() }
        val sheet = layoutInflater.inflate(R.layout.dialog_preset_picker, null)
        val list = sheet.findViewById<LinearLayout>(R.id.list)
        val scroll = sheet.findViewById<ScrollView>(R.id.scroll)
        scroll.layoutParams.height = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        sheet.findViewById<TextView>(R.id.title).text = if (position < presets.size) "Choose preset" else "Add preset"

        val rows = (0 until HornetProtocol.PRESET_COUNT).map { preset ->
            if (preset % HornetProtocol.SLOT_COUNT == 0) {
                val header = layoutInflater.inflate(R.layout.item_picker_bank, list, false) as TextView
                header.text = "BANK ${preset / HornetProtocol.SLOT_COUNT}"
                list.addView(header)
            }
            layoutInflater.inflate(R.layout.item_picker_preset, list, false).also { row ->
                row.findViewById<View>(R.id.dot).background.mutate().setTint(slotColor(preset))
                row.findViewById<TextView>(R.id.code).text = HornetProtocol.presetName(preset)
                row.findViewById<TextView>(R.id.name).text = settings.presetName(preset) ?: "—"
                list.addView(row)
            }
        }
        fun highlight() = rows.forEachIndexed { preset, row ->
            row.setBackgroundResource(if (preset == choice) R.drawable.bg_picker_selected else R.drawable.bg_picker_row)
        }
        highlight()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        rows.forEachIndexed { preset, row ->
            row.setOnClickListener {
                choice = preset
                highlight()
                preview(preset)
            }
        }
        sheet.findViewById<View>(R.id.cancel).setOnClickListener { dialog.dismiss() }
        sheet.findViewById<View>(R.id.select).setOnClickListener {
            if (position < presets.size) presets[position] = choice else presets.add(choice)
            renderPresets()
            dialog.dismiss()
        }
        dialog.show()
        preview(choice)
        scroll.post { scroll.scrollTo(0, rows[choice].top - scroll.height / 3) }
    }

    /** Swiping the row left reveals its Delete button; a tap or a swipe back closes it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeToReveal(content: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val revealWidth = resources.getDimension(R.dimen.delete_reveal_width)
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var swiping = false
        content.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = v.translationX
                    swiping = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!swiping && presets.size > 1 && abs(dx) > slop && abs(dx) > abs(e.rawY - downY)) {
                        swiping = true
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        if (openRow != v) closeOpenRow()
                    }
                    if (swiping) v.translationX = (startX + dx).coerceIn(-revealWidth, 0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when {
                    swiping -> {
                        val open = v.translationX < -revealWidth / 2
                        v.animate().translationX(if (open) -revealWidth else 0f).setDuration(150).start()
                        openRow = if (open) v else null
                    }
                    e.actionMasked != MotionEvent.ACTION_UP -> Unit
                    v.translationX != 0f -> closeOpenRow()
                    openRow != null -> closeOpenRow()
                    else -> v.performClick()
                }
            }
            true
        }
    }

    private fun closeOpenRow() {
        openRow?.animate()?.translationX(0f)?.setDuration(150)?.start()
        openRow = null
    }

    /** Plays the preset on the amp; if the amp is still switching, the latest choice is sent once it is free. */
    private fun preview(preset: Int) {
        previewTarget = preset
        handler.removeCallbacks(previewRetry)
        sendPreview()
    }

    private fun sendPreview() {
        val preset = previewTarget ?: return
        val client = MainActivity.sharedClient
        when (client?.state) {
            HornetState.READY, HornetState.COMMAND_FAILED -> {
                previewTarget = null
                client.selectPreset(preset, SystemClock.uptimeMillis())
            }
            HornetState.SWITCHING -> handler.postDelayed(previewRetry, PREVIEW_RETRY_MS)
            else -> {
                previewTarget = null
                Toast.makeText(this, "Hornet is not connected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Dragging the handle lifts the row and slides the others out of the way; the new order is applied on release. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachReorder(row: View, handle: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startY = 0f
        var dragging = false
        var from = 0
        var target = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    dragging = false
                    handle.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (!dragging && presets.size > 1 && abs(dy) > slop) {
                        dragging = true
                        from = presetsList.indexOfChild(row)
                        target = from
                        closeOpenRow()
                        row.elevation = resources.displayMetrics.density * 12
                        row.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
                    }
                    if (dragging) {
                        val step = (presetsList.getChildAt(1).top - presetsList.getChildAt(0).top).toFloat()
                        val offset = dy.coerceIn(-from * step, (presets.lastIndex - from) * step)
                        row.translationY = offset
                        val newTarget = (from + (offset / step).roundToInt()).coerceIn(0, presets.lastIndex)
                        if (newTarget != target) {
                            target = newTarget
                            for (i in 0 until presetsList.childCount) {
                                if (i == from) continue
                                val shift = when {
                                    i in (from + 1)..target -> -step
                                    i in target until from -> step
                                    else -> 0f
                                }
                                presetsList.getChildAt(i).animate().translationY(shift).setDuration(150).start()
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    presets.add(target, presets.removeAt(from))
                    renderPresets()
                }
            }
            true
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${nameEdit.text}\"?")
            .setPositiveButton("Delete") { _, _ ->
                val current = settings.currentSetupIndex
                settings.setups = settings.setups.toMutableList().also { it.removeAt(index) }
                settings.currentSetupIndex = if (current >= index && current > 0) current - 1 else current
                deleted = true
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val PREVIEW_RETRY_MS = 100L
        const val EXTRA_INDEX = "setup_index"
    }
}
