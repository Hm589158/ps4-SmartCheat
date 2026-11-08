package com.ps4smartcheat.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.edit.EditPolicy
import com.ps4smartcheat.core.scan.BackendPreference
import com.ps4smartcheat.core.scan.RegionFilter
import com.ps4smartcheat.core.scan.ScanOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class AppSettings(
    val host: String = "",
    val port: Int = ConnectOptions.DEFAULT_PORT,
    val connectTimeoutMs: Int = 5_000,
    val readTimeoutMs: Int = 10_000,
    val queryFirmware: Boolean = true,
    val allowNonLocalHosts: Boolean = false,
    val editingEnabled: Boolean = false,
    val allowNonWritableRegions: Boolean = false,
    val allowSystemProcesses: Boolean = false,
    val includeReadOnlyRegions: Boolean = false,
    val includeExecutableRegions: Boolean = false,
    val maxResults: Int = 1_000_000,
    val chunkKb: Int = 1024,
    val unalignedScan: Boolean = false,
    val pageSize: Int = 50,
    val theme: ThemeMode = ThemeMode.DARK,
    val scanBackend: BackendPreference = BackendPreference.AUTO,
    val turboAliasing: Boolean = false,
    val probeServerScan: Boolean = true,
    /** Live refresh interval for result lists and the memory viewer. */
    val monitorIntervalMs: Int = 1000,
    /** Skip regions larger than this many MiB during scans (0 = no limit). */
    val maxRegionMiB: Int = 0,
    /** Select the running game automatically after connecting / when it restarts. */
    val autoSelectGame: Boolean = true,
) {
    fun connectOptions() = ConnectOptions(
        host = host, port = port, connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs,
        queryFirmware = queryFirmware, allowNonLocalHosts = allowNonLocalHosts, probeServerScan = probeServerScan,
        // Wi-Fi waking up (phone screen off, app in the background) can take several seconds: 0.5+1+2+4+8 s.
        reconnectAttempts = 5,
    )

    fun editPolicy() = EditPolicy(editingEnabled, allowNonWritableRegions, allowSystemProcesses)

    fun regionFilter() = RegionFilter(
        includeReadOnly = includeReadOnlyRegions,
        includeExecutable = includeExecutableRegions,
        maxRegionBytes = maxRegionMiB.coerceAtLeast(0).toLong() * 1024 * 1024,
    )

    fun scanOptions() = ScanOptions(
        chunkSize = chunkKb.coerceIn(4, 4096) * 1024,
        maxResults = maxResults.coerceIn(1_000, 5_000_000),
        alignment = if (unalignedScan) 1 else 0,
        backend = scanBackend,
        turboAliasing = turboAliasing,
    )
}

/** SharedPreferences-backed settings exposed as a StateFlow. Contains no credentials. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val value: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        write(next)
        _settings.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            host = prefs.getString("host", d.host) ?: d.host,
            port = prefs.getInt("port", d.port),
            connectTimeoutMs = prefs.getInt("connectTimeoutMs", d.connectTimeoutMs),
            readTimeoutMs = prefs.getInt("readTimeoutMs", d.readTimeoutMs),
            queryFirmware = prefs.getBoolean("queryFirmware", d.queryFirmware),
            allowNonLocalHosts = prefs.getBoolean("allowNonLocalHosts", d.allowNonLocalHosts),
            // Editing always starts disabled after an app restart: read-only by default.
            editingEnabled = false,
            allowNonWritableRegions = prefs.getBoolean("allowNonWritableRegions", d.allowNonWritableRegions),
            allowSystemProcesses = false,
            includeReadOnlyRegions = prefs.getBoolean("includeReadOnlyRegions", d.includeReadOnlyRegions),
            includeExecutableRegions = prefs.getBoolean("includeExecutableRegions", d.includeExecutableRegions),
            maxResults = prefs.getInt("maxResults", d.maxResults),
            chunkKb = prefs.getInt("chunkKb", d.chunkKb),
            unalignedScan = prefs.getBoolean("unalignedScan", d.unalignedScan),
            pageSize = prefs.getInt("pageSize", d.pageSize),
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            scanBackend = runCatching { BackendPreference.valueOf(prefs.getString("scanBackend", d.scanBackend.name)!!) }.getOrDefault(d.scanBackend),
            turboAliasing = prefs.getBoolean("turboAliasing", d.turboAliasing),
            probeServerScan = prefs.getBoolean("probeServerScan", d.probeServerScan),
            monitorIntervalMs = prefs.getInt("monitorIntervalMs", d.monitorIntervalMs),
            maxRegionMiB = prefs.getInt("maxRegionMiB", d.maxRegionMiB),
            autoSelectGame = prefs.getBoolean("autoSelectGame", d.autoSelectGame),
        )
    }

    private fun write(s: AppSettings) {
        prefs.edit()
            .putString("host", s.host)
            .putInt("port", s.port)
            .putInt("connectTimeoutMs", s.connectTimeoutMs)
            .putInt("readTimeoutMs", s.readTimeoutMs)
            .putBoolean("queryFirmware", s.queryFirmware)
            .putBoolean("allowNonLocalHosts", s.allowNonLocalHosts)
            .putBoolean("allowNonWritableRegions", s.allowNonWritableRegions)
            .putBoolean("includeReadOnlyRegions", s.includeReadOnlyRegions)
            .putBoolean("includeExecutableRegions", s.includeExecutableRegions)
            .putInt("maxResults", s.maxResults)
            .putInt("chunkKb", s.chunkKb)
            .putBoolean("unalignedScan", s.unalignedScan)
            .putInt("pageSize", s.pageSize)
            .putString("theme", s.theme.name)
            .putString("scanBackend", s.scanBackend.name)
            .putBoolean("turboAliasing", s.turboAliasing)
            .putBoolean("probeServerScan", s.probeServerScan)
            .putInt("monitorIntervalMs", s.monitorIntervalMs)
            .putInt("maxRegionMiB", s.maxRegionMiB)
            .putBoolean("autoSelectGame", s.autoSelectGame)
            .apply()
    }
}
