package dev.hornetswitch

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Setup(val name: String, val presets: List<Int>)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var setups: List<Setup>
        get() {
            val default = listOf(Setup("Setup 1", listOf(6, 7)))
            val json = prefs.getString("setups", null) ?: return default
            val parsed = try {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    val presets = item.optJSONArray("presets") ?: return@mapNotNull null
                    val valid = (0 until presets.length()).map { presets.optInt(it, -1) }
                        .filter { it in 0 until HornetProtocol.PRESET_COUNT }
                    if (valid.isEmpty()) null else Setup(item.optString("name").ifBlank { "Setup ${i + 1}" }, valid)
                }
            } catch (e: JSONException) {
                emptyList()
            }
            return parsed.ifEmpty { default }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { setup ->
                array.put(JSONObject().put("name", setup.name).put("presets", JSONArray(setup.presets)))
            }
            prefs.edit().putString("setups", array.toString()).apply()
        }

    var currentSetupIndex: Int
        get() = prefs.getInt("current_setup", 0).coerceIn(0, setups.lastIndex)
        set(value) = prefs.edit().putInt("current_setup", value).apply()

    val currentSetup: Setup
        get() = setups[currentSetupIndex]

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(value) = prefs.edit().putBoolean("vibration", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(value) = prefs.edit().putBoolean("auto_reconnect", value).apply()

    var deviceAddress: String?
        get() = prefs.getString("device_address", null)
        set(value) = prefs.edit().putString("device_address", value).apply()

    var deviceName: String?
        get() = prefs.getString("device_name", null)
        set(value) = prefs.edit().putString("device_name", value).apply()

    /**
     * @param index global preset index
     * @return name last read from the amp, or null if not loaded yet
     */
    fun presetName(index: Int): String? = prefs.getString("preset_name_$index", null)

    /**
     * @param index global preset index
     * @param name name read from the amp
     */
    fun setPresetName(index: Int, name: String) = prefs.edit().putString("preset_name_$index", name).apply()

    /** Drops cached names, e.g. when switching to another amp. */
    fun clearPresetNames() {
        val editor = prefs.edit()
        (0 until HornetProtocol.PRESET_COUNT).forEach { editor.remove("preset_name_$it") }
        editor.apply()
    }
}
