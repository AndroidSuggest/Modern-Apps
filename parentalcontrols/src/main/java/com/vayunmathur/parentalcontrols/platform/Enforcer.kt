package com.vayunmathur.parentalcontrols.platform

import android.content.Context
import android.util.Log
import com.vayunmathur.parentalcontrols.data.AppRule
import com.vayunmathur.parentalcontrols.data.BonusGrant
import com.vayunmathur.parentalcontrols.data.SupervisionRules
import com.vayunmathur.parentalcontrols.notifications.LockNotifier
import java.time.LocalDate
import java.time.LocalDateTime

private const val TAG = "ParentalControlsEnforcer"

/**
 * Decides what each supervised app's state should be, and makes it so.
 *
 * Everything that can change an outcome funnels through [reconcile]: the bedtime alarm, a usage
 * observer firing, a rule edit, and boot. Recomputing the whole picture each time is what keeps
 * the four of them from disagreeing - an incremental "block this one app" path would have to
 * know whether bedtime was also active, and would get it wrong at exactly the boundary where it
 * matters.
 *
 * ## Precedence
 *
 * Bedtime > downtime > school time > device-wide daily limit > per-app limit. All are reasons
 * to block and none is a reason to unblock while another holds, so the state is simply the OR
 * of them; the ordering only matters for the *reason* shown to the user.
 *
 * ## Why a limit trip is remembered
 *
 * The platform observer fires once when the budget runs out and does not fire again. If we
 * treated "blocked" as derived purely from live usage, the next [reconcile] - a bedtime
 * boundary, say - would unblock the app because nothing in the current state says the limit was
 * reached. [LimitState] records the trip for the rest of the local day, and midnight clears it.
 */
class Enforcer(private val context: Context) {

    private val rules = SupervisionRules.get(context)
    private val policies = SupervisionPolicies(context)
    private val limits = AppLimits(context)
    private val bedtime = BedtimeScheduler(context)
    private val limitState = LimitState(context)

    /** Note that [packageName] has used up its daily budget, then reconcile. */
    suspend fun onLimitReached(packageName: String) {
        limitState.markReached(packageName)
        reconcile()
        // The observer fires exactly once per trip, so this notifies exactly once: the child
        // learns why the app just died. Window entries are notified by onWindowBoundary.
        LockNotifier.notifyLocked(context, BlockReason.AppLimit, packageName)
    }

    /**
     * Called from the window-boundary receiver after [reconcile].
     *
     * Notifies only for windows that just *opened* (closed a minute ago, open now), and only
     * the apps that window blocks - so a boundary that ends a window stays silent, and an
     * already-crying child is not re-notified for apps blocked all along by another reason.
     */
    suspend fun onWindowBoundary() {
        reconcile()
        val now = LocalDateTime.now()
        val minuteAgo = now.minusMinutes(1)
        val bedtime = rules.scheduleNow()
        val downtime = rules.downtimeNow()
        val school = rules.schoolTimeNow()
        val all = rules.allRulesNow()
        val day = LocalDate.now().toString()
        val deviceOverBudget = deviceOverBudgetNow(rules.bonusesToday(day))
        if (bedtime.activeAt(now) && !bedtime.activeAt(minuteAgo)) {
            for (rule in all) {
                if (rule.blockedAtBedtime) {
                    LockNotifier.notifyLocked(context, BlockReason.Bedtime, rule.packageName)
                }
            }
        }
        if (downtime.activeAt(now) && !downtime.activeAt(minuteAgo)) {
            for (rule in all) {
                if (!rule.allowedInDowntime &&
                    !(rule.blockedAtBedtime && bedtime.activeAt(now))
                ) {
                    LockNotifier.notifyLocked(context, BlockReason.Downtime, rule.packageName)
                }
            }
        }
        if (school.activeAt(now) && !school.activeAt(minuteAgo)) {
            for (rule in all) {
                if (!rule.allowedInDowntime &&
                    !(rule.blockedAtBedtime && bedtime.activeAt(now)) &&
                    !(downtime.activeAt(now))
                ) {
                    LockNotifier.notifyLocked(context, BlockReason.SchoolTime, rule.packageName)
                }
            }
        }
        // Device-wide budget crossing is detected here rather than by an observer (the
        // platform has no device-total primitive): if the last minute pushed the total over,
        // the child gets one notice. Per-app trips already notified via onLimitReached.
        if (!deviceOverBudget) {
            val overNow = deviceOverBudgetNow(rules.bonusesToday(LocalDate.now().toString()))
            if (overNow) LockNotifier.notifyLocked(context, BlockReason.DailyLimit, null)
        }
    }

    /**
     * Bring the device in line with the stored rules.
     *
     * Safe to call redundantly - writing a policy that already matches is a no-op to the user,
     * and the platform de-duplicates the resulting hidden-state change.
     */
    suspend fun reconcile() {
        if (!policies.isAvailable) {
            Log.w(TAG, "no supervision policy channel; nothing will be enforced")
            return
        }
        // Master switch off ("Controls for this phone"): lift every block, stop timing, and
        // arm nothing. The child's device behaves as if unsupervised until it is turned on.
        if (!SupervisionMaster.isEnabled(context)) {
            val all = rules.allRulesNow()
            for (rule in all) policies.allow(rule.packageName)
            limits.sync(emptyList(), emptyList())
            bedtime.armAll(
                rules.scheduleNow().copy(enabled = false),
                rules.downtimeNow().copy(enabled = false),
                rules.schoolTimeNow().copy(enabled = false),
            )
            return
        }
        val now = LocalDateTime.now()
        val bedtimeSchedule = rules.scheduleNow()
        val downtimeSchedule = rules.downtimeNow()
        val schoolSchedule = rules.schoolTimeNow()
        val all = rules.allRulesNow()
        val bedtimeNow = bedtimeSchedule.activeAt(now)
        val downtimeNow = downtimeSchedule.activeAt(now)
        val schoolNow = schoolSchedule.activeAt(now)
        val day = LocalDate.now().toString()
        val bonuses = rules.bonusesToday(day)
        limitState.pruneToToday()
        // Cheap and idempotent, and it has to happen before AppLimits.sync below: without the
        // usage-stats op an observer is armed with timeUsed = 0.
        if (UsageAccess.ensure(context) == UsageAccess.Status.DENIED) {
            Log.w(TAG, "usage-stats op denied; limits measure from arm time until it is granted")
        }

        val deviceOverBudget = deviceOverBudgetNow(bonuses)

        for (rule in all) {
            val reason = blockReason(
                rule = rule,
                bedtimeNow = bedtimeNow,
                downtimeNow = downtimeNow,
                schoolNow = schoolNow,
                deviceOverBudget = deviceOverBudget,
            )
            if (reason != null) policies.block(rule.packageName)
            else policies.allow(rule.packageName)
        }

        // Record the parent's caps in the platform's own policy store. Does not enforce - see
        // SupervisionPolicies.limit - but makes the intent visible to getPolicies and to anything
        // that inspects supervision state later.
        for (rule in all) {
            rule.dailyLimitMinutes?.let { policies.limit(rule.packageName, it) }
        }

        limits.sync(all.filter { blockReason(
            rule = it,
            bedtimeNow = bedtimeNow,
            downtimeNow = downtimeNow,
            schoolNow = schoolNow,
            deviceOverBudget = deviceOverBudget,
        ) == null }, bonuses)
        bedtime.armAll(bedtimeSchedule, downtimeSchedule, schoolSchedule)
    }

    /**
     * Why [rule]'s app is currently blocked, or null when it may run.
     *
     * Order is bedtime > downtime > school time > device daily limit > per-app limit; the first
     * holding reason wins so the lock screen can name it.
     */
    private fun blockReason(
        rule: AppRule,
        bedtimeNow: Boolean,
        downtimeNow: Boolean,
        schoolNow: Boolean,
        deviceOverBudget: Boolean,
    ): BlockReason? {
        if (rule.blockedAtBedtime && bedtimeNow) return BlockReason.Bedtime
        if (downtimeNow && !rule.allowedInDowntime) return BlockReason.Downtime
        if (schoolNow && !rule.allowedInDowntime) return BlockReason.SchoolTime
        if (deviceOverBudget) return BlockReason.DailyLimit
        if (limitState.hasReached(rule.packageName)) return BlockReason.AppLimit
        return null
    }

    /** The user-facing reason an app is blocked. Ordering matches [blockReason] precedence. */
    enum class BlockReason {
        Bedtime,
        Downtime,
        SchoolTime,
        DailyLimit,
        AppLimit,
    }

    /**
     * Whether the device-wide budget (cap plus today's device-wide bonuses) is spent.
     *
     * Takes the already-loaded bonuses so [reconcile] does not read the table twice.
     */
    private suspend fun deviceOverBudgetNow(bonuses: List<BonusGrant>): Boolean {
        val deviceBonus = bonuses.filter { it.packageName == null }.sumOf { it.bonusMinutes }
        val deviceLimit = rules.dailyLimitNow()?.let { it + deviceBonus } ?: return false
        return limits.totalUsedToday() >= java.time.Duration.ofMinutes(deviceLimit.toLong())
    }
}

/**
 * Which apps have used up today's budget.
 *
 * Deliberately not in Room: it is per-day scratch state that must be readable from a broadcast
 * receiver on the main thread before any coroutine is available, and it is worthless after
 * midnight. SharedPreferences is the right weight for that.
 */
class LimitState(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markReached(packageName: String) {
        pruneToToday()
        val next = reached() + packageName
        prefs.edit().putStringSet(KEY_REACHED, next).putString(KEY_DAY, today()).apply()
    }

    fun hasReached(packageName: String): Boolean = packageName in reached()

    /** Drop yesterday's trips. This is the daily reset; there is no other. */
    fun pruneToToday() {
        if (prefs.getString(KEY_DAY, null) == today()) return
        prefs.edit().remove(KEY_REACHED).putString(KEY_DAY, today()).apply()
    }

    private fun reached(): Set<String> = prefs.getStringSet(KEY_REACHED, emptySet()) ?: emptySet()

    private fun today(): String = LocalDate.now().toString()

    private companion object {
        const val PREFS = "parentalcontrols_limits"
        const val KEY_REACHED = "reached"
        const val KEY_DAY = "day"
    }
}
