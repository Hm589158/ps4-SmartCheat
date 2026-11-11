package com.ps4smartcheat.app.ui.tabs

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.tabs.TabSpec
import com.ps4smartcheat.core.tabs.Tool
import com.ps4smartcheat.core.tabs.ToolArgs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the open tabs (tool + arguments, order, active tab) across app restarts. Only what is needed to reopen
 * them is stored; a tool's work (scan results, a running watch…) lives in its view model and app-wide state.
 */
class TabStore(context: Context) {
    private val prefs = context.getSharedPreferences("tabs", Context.MODE_PRIVATE)

    fun load(): Pair<List<TabSpec>, String?> = try {
        val root = JSONObject(prefs.getString(KEY, null) ?: return emptyList<TabSpec>() to null)
        val arr = root.getJSONArray("tabs")
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val tool = runCatching { Tool.valueOf(o.getString("tool")) }.getOrNull() ?: return@mapNotNull null
            TabSpec(
                o.getString("id"), tool,
                ToolArgs(
                    address = if (o.has("address")) o.getLong("address") else null,
                    type = if (o.has("type")) runCatching { ValueType.valueOf(o.getString("type")) }.getOrNull() else null,
                    size = if (o.has("size")) o.getInt("size") else null,
                    writes = if (o.has("writes")) o.getBoolean("writes") else null,
                ),
            )
        }.distinctBy { it.id }
        list to root.optString("active").ifBlank { null }
    } catch (e: Exception) {
        emptyList<TabSpec>() to null // unreadable: start with the default tabs
    }

    fun save(tabs: List<TabSpec>, active: String?) {
        val arr = JSONArray()
        tabs.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("tool", t.tool.name)
                    t.args.address?.let { put("address", it) }
                    t.args.type?.let { put("type", it.name) }
                    t.args.size?.let { put("size", it) }
                    t.args.writes?.let { put("writes", it) }
                },
            )
        }
        prefs.edit().putString(KEY, JSONObject().put("tabs", arr).put("active", active ?: "").toString()).apply()
    }

    private companion object { const val KEY = "tabs-v1" }
}

/**
 * One [ViewModelStore] per tab, kept for the life of the app process (also across screen rotation), so a tab's view
 * models survive switching to other tabs. Cleared when the tab is closed.
 */
class TabViewModelStores {
    private val stores = HashMap<String, ViewModelStore>()

    @Synchronized
    fun owner(id: String): ViewModelStoreOwner {
        val store = stores.getOrPut(id) { ViewModelStore() }
        return object : ViewModelStoreOwner { override val viewModelStore: ViewModelStore = store }
    }

    /** Clears the tab's view models (their onCleared runs: e.g. a watch session is released). */
    @Synchronized
    fun clear(id: String) { stores.remove(id)?.clear() }
}
