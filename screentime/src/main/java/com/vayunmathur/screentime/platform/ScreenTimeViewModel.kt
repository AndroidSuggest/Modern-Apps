package com.vayunmathur.screentime.platform

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.screentime.data.AppTimer
import com.vayunmathur.screentime.data.FocusProfile
import com.vayunmathur.screentime.data.ScreenTimeRules
import com.vayunmathur.screentime.data.WindDownSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One launchable app with today's usage and its timer, if any. */
data class TimedApp(
    val packageName: String,
    val label: String,
    val usedMinutes: Long,
    val timer: AppTimer?,
)

/** Everything the Screen Time screens draw. */
data class ScreenTimeUiState(
    val totalMinutes: Long = 0,
    val apps: List<TimedApp> = emptyList(),
    val focus: FocusProfile = FocusProfile(),
    val focusRunning: Boolean = false,
    val windDown: WindDownSchedule = WindDownSchedule(),
    val loading: Boolean = true,
)

class ScreenTimeViewModel(app: Application) : AndroidViewModel(app) {

    private val rules = ScreenTimeRules.get(app)
    private val timers = AppTimers(app)
    private val installed = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private val usage = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val focusRunning = MutableStateFlow(false)

    val state: StateFlow<ScreenTimeUiState> = combine(
        rules.allTimers,
        rules.focusProfile,
        rules.windDownSchedule,
        installed,
        usage,
        focusRunning,
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val timerList = flows[0] as List<AppTimer>
        val focus = flows[1] as FocusProfile
        val windDown = flows[2] as WindDownSchedule
        @Suppress("UNCHECKED_CAST")
        val apps = flows[3] as List<Pair<String, String>>
        @Suppress("UNCHECKED_CAST")
        val used = flows[4] as Map<String, Long>
        val running = flows[5] as Boolean
        val byPackage = timerList.associateBy { it.packageName }
        val timed = apps.mapNotNull { (pkg, label) ->
            val minutes = (used[pkg] ?: 0L) / 60_000L
            if (minutes == 0L && byPackage[pkg] == null) null
            else TimedApp(pkg, label, minutes, byPackage[pkg])
        }.sortedByDescending { it.usedMinutes }
        ScreenTimeUiState(
            totalMinutes = (used.values.sum() / 60_000L),
            apps = timed,
            focus = focus,
            focusRunning = running,
            windDown = windDown,
            loading = apps.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScreenTimeUiState())

    init {
        viewModelScope.launch { installed.value = loadLaunchableApps() }
        refresh()
    }

    /** Re-reads usage and focus state; called on resume and after every mutation. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            UsageAccess.ensure(getApplication())
            usage.value = timers.usageTodayByPackage()
            focusRunning.value = Coordinator(getApplication()).isFocusActive()
        }
    }

    fun setTimer(packageName: String, minutes: Int?) {
        viewModelScope.launch {
            if (minutes == null) {
                rules.timer(packageName)?.let { rules.delete(it) }
            } else {
                rules.upsert(AppTimer(packageName, minutes))
            }
            reconcile()
            refresh()
        }
    }

    fun setFocusActive(active: Boolean) {
        viewModelScope.launch {
            Coordinator(getApplication()).setFocusActive(active)
            refresh()
        }
    }

    fun setFocusPaused(packageName: String, paused: Boolean) {
        viewModelScope.launch {
            val current = rules.focusNow()
            val next = current.pausedPackages.toMutableSet()
            if (paused) next.add(packageName) else next.remove(packageName)
            rules.setFocus(current.copy(pausedPackages = next.toList()))
            reconcile()
        }
    }

    fun setFocusSchedule(
        enabled: Boolean? = null,
        startMinute: Int? = null,
        endMinute: Int? = null,
        daysMask: Int? = null,
    ) {
        viewModelScope.launch {
            val current = rules.focusNow()
            rules.setFocus(
                current.copy(
                    scheduleEnabled = enabled ?: current.scheduleEnabled,
                    startMinute = startMinute ?: current.startMinute,
                    endMinute = endMinute ?: current.endMinute,
                    daysMask = daysMask ?: current.daysMask,
                ),
            )
            reconcile()
        }
    }

    fun setWindDown(
        enabled: Boolean? = null,
        startMinute: Int? = null,
        endMinute: Int? = null,
        daysMask: Int? = null,
        grayscale: Boolean? = null,
        doNotDisturb: Boolean? = null,
    ) {
        viewModelScope.launch {
            val current = rules.windDownNow()
            rules.setWindDown(
                current.copy(
                    enabled = enabled ?: current.enabled,
                    startMinute = startMinute ?: current.startMinute,
                    endMinute = endMinute ?: current.endMinute,
                    daysMask = daysMask ?: current.daysMask,
                    grayscale = grayscale ?: current.grayscale,
                    doNotDisturb = doNotDisturb ?: current.doNotDisturb,
                ),
            )
            reconcile()
        }
    }

    private suspend fun reconcile() = withContext(Dispatchers.IO) {
        Coordinator(getApplication()).reconcile()
    }

    private suspend fun loadLaunchableApps(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val self = getApplication<Application>().packageName
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != self }
                .map { it.packageName to it.label(pm) }
                .sortedBy { it.second.lowercase() }
        }

    private fun ApplicationInfo.label(pm: PackageManager): String =
        runCatching { pm.getApplicationLabel(this).toString() }.getOrDefault(packageName)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
