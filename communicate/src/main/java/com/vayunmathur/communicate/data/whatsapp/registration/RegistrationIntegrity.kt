package com.vayunmathur.communicate.data.whatsapp.registration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Device-integrity signal collector for the WAMSYS `/v2` endpoints (w2.md §2.3, Phase B 2a).
 *
 * Computes the honestly-available integrity signals:
 *  - `aid` = base64(SHA-256(Android ID))                              (44B)
 *  - `_gi` = ENC(JSON{apk sha256, sourceDir, cert sha256, size, pkg}) (encrypted query string)
 *  - `_gp` = base64(SHA-256(sorted manifest permissions))            (44B)
 *  - `_ge` = {"sv":<virtio>,"sb":<vboxsf>}                            (emulation probe)
 *  - `_ga` = {"mp","mu","ae","ap","ai"}                              (automation signals)
 *  - `_gs` = {"em":"<base64>"}                                       (native-obfuscation placeholder)
 *  - `t`   = base64(int64 BE attestation timestamp seconds)          (8B)
 *  - `db`  = ADB-enabled flag (Settings.Global.ADB_ENABLED)          (0|1)
 *
 * NOT computed (bound to the official signed WhatsApp app identity; no FOSS way to mint):
 *  - `gpia` / `_gg` (Play Integrity JWT) and `recaptcha` (reCAPTCHA Enterprise). These are omitted
 *    entirely — an unofficial client cannot produce server-valid tokens (documented, not faked).
 *
 * The pure encoders ([aidOf], [permissionsHashOf], [tField], [emulationJson], [automationJson],
 * [nativeSignalsJson]) use `java.util.Base64` (API 26+, minSdk 31) so they are JVM-unit-testable.
 */
object RegistrationIntegrity {

    private val b64 = Base64.getEncoder()

    // ---------------------------------------------------------------- pure encoders (JVM-testable)

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** `aid` = base64(SHA-256(Android ID)). Standard base64 (44 chars for a 32-byte digest). */
    fun aidOf(androidId: String): String = b64.encodeToString(sha256(androidId.toByteArray(Charsets.UTF_8)))

    /** `_gp` = base64(SHA-256(newline-joined sorted permission names)). */
    fun permissionsHashOf(permissions: List<String>): String =
        b64.encodeToString(sha256(permissions.sorted().joinToString("\n").toByteArray(Charsets.UTF_8)))

    /** `t` = base64(8-byte big-endian int64 seconds). */
    fun tField(epochSeconds: Long): String =
        b64.encodeToString(ByteBuffer.allocate(8).putLong(epochSeconds).array())

    /** `_ge` emulation probe JSON `{"sv":<virtio>,"sb":<vboxsf>}` (stable key order). */
    fun emulationJson(virtio: Boolean, vboxsf: Boolean): String =
        """{"sv":$virtio,"sb":$vboxsf}"""

    /** `_ga` automation-signals JSON `{"mp","mu","ae","ap","ai"}` (stable key order). */
    fun automationJson(mockPackages: Boolean, multiUser: Boolean, ae: Long, ap: Long, ai: Long): String =
        """{"mp":$mockPackages,"mu":$multiUser,"ae":$ae,"ap":$ap,"ai":$ai}"""

    /**
     * `_gs` native-obfuscation placeholder JSON `{"em":"<base64>"}`. The real `_gs` is produced by a
     * native RFC we cannot reproduce; we ship the exact wire shape filled with an available signal
     * (or empty). Ref w2.md §2.3 `wa-android-native-obfuscation-rfc`.
     */
    fun nativeSignalsJson(emBase64: String): String = """{"em":"$emBase64"}"""

    // ---------------------------------------------------------------- Android collection

    /** The set of collected integrity signals (empty strings mean "not available / not sent"). */
    data class Signals(
        val aid: String,
        val gp: String,
        val ge: String,
        val ga: String,
        val gs: String,
        val gi: String?,
        val db: String,
        val tSeconds: Long,
    )

    /**
     * Collect all honestly-available integrity signals from [context].
     * @param encryptQueryString the ENC wrapper used for `_gi` (defaults to
     *   [RegistrationAttestation.encryptQueryString]); may return null on failure.
     */
    fun collect(
        context: Context,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        encryptQueryString: (String) -> String? = RegistrationAttestation::encryptQueryString,
    ): Signals {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        }.getOrDefault("")

        val aid = if (androidId.isNotEmpty()) aidOf(androidId) else ""
        val gp = permissionsHashOf(manifestPermissions(context))
        val ge = emulationJson(virtio = probeVirtio(), vboxsf = probeVboxsf())
        val ga = automationJson(
            mockPackages = false,
            multiUser = false,
            ae = firstInstallTime(context),
            ap = lastUpdateTime(context),
            ai = 0L,
        )
        val gs = nativeSignalsJson("")
        val gi = buildGi(context, encryptQueryString)
        val db = runCatching {
            if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0) "1" else "0"
        }.getOrDefault("0")

        return Signals(aid = aid, gp = gp, ge = ge, ga = ga, gs = gs, gi = gi, db = db, tSeconds = nowSeconds)
    }

    private fun manifestPermissions(context: Context): List<String> = runCatching {
        val pkg = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        )
        pkg.requestedPermissions?.toList().orEmpty()
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun firstInstallTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime / 1000
    }.getOrDefault(0L)

    @Suppress("DEPRECATION")
    private fun lastUpdateTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime / 1000
    }.getOrDefault(0L)

    /** `_gi` = ENC(JSON{apk sha256, sourceDir, cert sha256, size, package}). Best-effort. */
    private fun buildGi(context: Context, encrypt: (String) -> String?): String? = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(context.packageName, 0)
        val sourceDir = context.applicationInfo.sourceDir
        val apkFile = File(sourceDir)
        val apkBytes = apkFile.takeIf { it.exists() }?.readBytes()
        val apkSha = apkBytes?.let { b64.encodeToString(sha256(it)) } ?: ""
        val size = apkBytes?.size ?: 0
        val json = buildString {
            append('{')
            append("\"apk_sha256\":\"").append(apkSha).append("\",")
            append("\"source_dir\":\"").append(sourceDir).append("\",")
            append("\"size\":").append(size).append(',')
            append("\"package\":\"").append(context.packageName).append("\"")
            append('}')
        }
        encrypt(json)
    }.getOrNull()

    private fun probeVirtio(): Boolean = runCatching {
        Build.HARDWARE.contains("virtio", ignoreCase = true) ||
            File("/proc/mounts").takeIf { it.exists() }?.readText()?.contains("virtio") == true
    }.getOrDefault(false)

    private fun probeVboxsf(): Boolean = runCatching {
        File("/proc/mounts").takeIf { it.exists() }?.readText()?.contains("vboxsf") == true
    }.getOrDefault(false)
}
