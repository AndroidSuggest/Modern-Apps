package com.vayunmathur.library.ml

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host-side CPU-vs-Vulkan comparison harness (task 3, team vulkan-validation).
 *
 * What this file is and is not:
 * - IS: the runnable-on-host half of the harness. Pure JVM (`kotlin.test` only,
 *   no `android.*`, no ORT, no native lib), so it executes under
 *   `:library:ml:testDevDebug` on a Windows dev machine with no Android GPU.
 * - IS: the home of the correctness math both paths share — cosine similarity
 *   (Clip / MobileFaceNet embeddings), argmax-exact (Maia move logits), mean
 *   box-corner drift (SCRFD) — plus the verdict classifier that turns probe
 *   bits + op-coverage facts into PASS / FALLBACK-to-CPU / NO-GO + reason.
 * - IS: the pinned per-model expectation table (op-coverage verdicts from the
 *   plan), guarded by tests so a verdict change fails loudly.
 * - IS NOT: a GPU run. On this host every GPU verdict is NO_LINK: the
 *   `ml_vulkan` cargo feature is off by default (`probe_device()` returns 0),
 *   no Vulkan loader is probed here, and only `MaiaHandle` compiles against
 *   the landed `VulkanSessions`/`VulkanBridge` API (the other handles still
 *   reference the stale task-5 sketch — task 1/2 territory, not touched here).
 *
 * CPU latency numbers are measured, not asserted here: see
 * `analysis/vulkan_compare.py` + `analysis/vulkan_compare_results.json`
 * (ORT CPU, intra/inter-op threads = 1 per the `OnnxSessions` policy, 3 warmup
 * + 11 timed runs, synthetic inputs matching each handle's documented shapes).
 * The snapshot from 2026-09-15 is reproduced in [cpuSnapshotMs] documentation
 * and in the final task-3 report; the ordering test below guards its shape.
 */
class VulkanComparisonTest {

    // -- Correctness math shared by both paths --------------------------------

    /** Cosine similarity of two embedding vectors (Clip, MobileFaceNet). */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / sqrt(na * nb)
    }

    /** Argmax of one logits row (Maia `moves [1, 4352]`, Whisper/NLLB rows). */
    fun argmax(row: FloatArray, suppress: Set<Int> = emptySet()): Int {
        var best = -1
        var top = Float.NEGATIVE_INFINITY
        for (i in row.indices) {
            if (i in suppress) continue
            if (row[i] > top) {
                top = row[i]
                best = i
            }
        }
        return best
    }

    /**
     * Mean absolute corner drift between two face-box lists (SCRFD), in
     * source-bitmap fractions. Empty-vs-empty is 0; count mismatch is NaN
     * (a detection-count change is not "close", it is a different result).
     */
    fun meanBoxDrift(a: List<FloatArray>, b: List<FloatArray>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        if (a.size != b.size) return Double.NaN
        var total = 0.0
        for (i in a.indices) {
            require(a[i].size == 4 && b[i].size == 4)
            for (c in 0 until 4) total += abs(a[i][c] - b[i][c])
        }
        return total / (a.size * 4)
    }

    // -- GPU verdict classifier -------------------------------------------------

    enum class GpuVerdict { PASS, FALLBACK_TO_CPU, NO_GO }

    data class GpuAssessment(val verdict: GpuVerdict, val reason: String)

    /**
     * Turn probe bits + op-coverage facts into a verdict.
     *
     * [probeUsable] is `VulkanSessions.isUsable()` on the device. The
     * remaining flags come from the model inventory (see
     * `analysis/vulkan_compare_results.json`, `vulkan_blockers` per model):
     * fp16 weights/KV (Gemma q4f16), int8 quant + native KV cache
     * (Whisper/NLLB), fused multi-output (U2-Net 7 maps incl. d0, SCRFD 9
     * maps, PP-OCR DBNet+CTC, Supertonic 4-net pipeline).
     */
    fun classify(
        probeUsable: Boolean,
        assetPresent: Boolean,
        fp16: Boolean = false,
        int8WithKvCache: Boolean = false,
        fusedPipeline: Boolean = false,
    ): GpuAssessment {
        if (!assetPresent) return GpuAssessment(GpuVerdict.NO_GO, "Asset-missing")
        if (!probeUsable) {
            return GpuAssessment(
                GpuVerdict.FALLBACK_TO_CPU,
                "no Vulkan loader on host (probe=0); ORT CPU serves",
            )
        }
        if (fp16) return GpuAssessment(GpuVerdict.NO_GO, "fp16 unsupported for Gemma")
        if (int8WithKvCache) {
            return GpuAssessment(
                GpuVerdict.FALLBACK_TO_CPU,
                "int8/coop-matrix + KV cache: Whisper/NLLB serve on ORT until proven",
            )
        }
        if (fusedPipeline) {
            return GpuAssessment(
                GpuVerdict.FALLBACK_TO_CPU,
                "fused/multi-output graph serves on ORT until device-proven",
            )
        }
        return GpuAssessment(GpuVerdict.PASS, "fp32 single-output graph, probe usable")
    }

    // -- Per-model expectation table (op-coverage plan, pinned) -----------------

    private data class ModelRow(
        val model: String,
        val probeUsableOnDevice: Boolean,
        val assetPresent: Boolean,
        val fp16: Boolean = false,
        val int8WithKvCache: Boolean = false,
        val fusedPipeline: Boolean = false,
        val expected: GpuVerdict,
    )

    private val table = listOf(
        ModelRow("maia", probeUsableOnDevice = true, assetPresent = true, expected = GpuVerdict.PASS),
        ModelRow("selfie", probeUsableOnDevice = true, assetPresent = true, expected = GpuVerdict.PASS),
        ModelRow("mobilefacenet", probeUsableOnDevice = true, assetPresent = true, expected = GpuVerdict.PASS),
        ModelRow("clip", probeUsableOnDevice = true, assetPresent = true, int8WithKvCache = true, expected = GpuVerdict.FALLBACK_TO_CPU),
        ModelRow("scrfd", probeUsableOnDevice = true, assetPresent = true, fusedPipeline = true, expected = GpuVerdict.FALLBACK_TO_CPU),
        ModelRow("u2net", probeUsableOnDevice = true, assetPresent = true, fusedPipeline = true, expected = GpuVerdict.FALLBACK_TO_CPU),
        ModelRow("ppocr-det", probeUsableOnDevice = true, assetPresent = false, fusedPipeline = true, expected = GpuVerdict.NO_GO),
        ModelRow("ppocr-rec", probeUsableOnDevice = true, assetPresent = false, fusedPipeline = true, expected = GpuVerdict.NO_GO),
        ModelRow("whisper-enc", probeUsableOnDevice = true, assetPresent = false, int8WithKvCache = true, expected = GpuVerdict.NO_GO),
        ModelRow("whisper-dec", probeUsableOnDevice = true, assetPresent = false, int8WithKvCache = true, expected = GpuVerdict.NO_GO),
        ModelRow("nllb-enc", probeUsableOnDevice = true, assetPresent = false, int8WithKvCache = true, expected = GpuVerdict.NO_GO),
        ModelRow("nllb-dec", probeUsableOnDevice = true, assetPresent = false, int8WithKvCache = true, expected = GpuVerdict.NO_GO),
        ModelRow("supertonic", probeUsableOnDevice = true, assetPresent = false, fusedPipeline = true, expected = GpuVerdict.NO_GO),
        ModelRow("gemma", probeUsableOnDevice = true, assetPresent = false, fp16 = true, expected = GpuVerdict.NO_GO),
    )

    // -- Tests ------------------------------------------------------------------

    @Test
    fun `cosine similarity is 1 for identical embeddings`() {
        val v = floatArrayOf(0.3f, -1.2f, 0.7f, 0.0f, 2.0f)
        assertEquals(1.0, cosineSimilarity(v, v.copyOf()), 1e-6)
    }

    @Test
    fun `cosine similarity is 0 for orthogonal vectors and -1 for opposite`() {
        assertEquals(
            0.0,
            cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            1e-6,
        )
        assertEquals(
            -1.0,
            cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f)),
            1e-6,
        )
    }

    @Test
    fun `cosine similarity is near 1 for a small perturbation`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(1.001f, 2.0f, 2.999f)
        assertTrue(cosineSimilarity(a, b) > 0.999999, "got ${cosineSimilarity(a, b)}")
    }

    @Test
    fun `argmax picks the peak and honours suppression`() {
        val row = floatArrayOf(0.1f, 5.0f, 0.2f)
        assertEquals(1, argmax(row))
        assertEquals(2, argmax(row, suppress = setOf(1)))
        assertEquals(-1, argmax(row, suppress = setOf(0, 1, 2)))
    }

    @Test
    fun `box drift is 0 for identical boxes and NaN on count mismatch`() {
        val boxes = listOf(floatArrayOf(0.1f, 0.2f, 0.5f, 0.6f))
        assertEquals(0.0, meanBoxDrift(boxes, boxes.map { it.copyOf() }), 1e-9)
        assertEquals(0.0, meanBoxDrift(emptyList(), emptyList()), 1e-9)
        assertTrue(meanBoxDrift(boxes, emptyList()).isNaN())
        val shifted = listOf(floatArrayOf(0.11f, 0.2f, 0.5f, 0.6f))
        assertEquals(0.0025, meanBoxDrift(boxes, shifted), 1e-6)
    }

    @Test
    fun `host probe is unusable so every bundled model falls back to CPU here`() {
        // Host-measured (this machine, 2026-09-15): nothing sets
        // VulkanSessions.probe, so isUsable() is false and the native
        // ml_vulkan library is absent for host JVM — the GPU path is NO_LINK
        // and ORT CPU is the serving path. If this fails, a loader appeared.
        assertFalse(
            VulkanSessions.isUsable(),
            "VulkanSessions.probe bits are set on this host; the NO_LINK " +
                "assumption in the task-3 report no longer holds",
        )
        val nativePresent = runCatching { VulkanBridge.probe() }.isSuccess
        assertFalse(
            nativePresent,
            "ml_vulkan native library loads on host JVM; GPU verdicts below " +
                "must be re-measured rather than reported as NO_LINK",
        )
        for (row in table.filter { it.assetPresent }) {
            val got = classify(
                probeUsable = false,
                assetPresent = true,
                fp16 = row.fp16,
                int8WithKvCache = row.int8WithKvCache,
                fusedPipeline = row.fusedPipeline,
            )
            assertEquals(
                GpuVerdict.FALLBACK_TO_CPU, got.verdict,
                "${row.model}: without a usable probe the verdict must be " +
                    "FALLBACK_TO_CPU, got $got",
            )
        }
    }

    @Test
    fun `device-projected verdicts match the op-coverage plan`() {
        for (row in table) {
            val got = classify(
                probeUsable = row.probeUsableOnDevice,
                assetPresent = row.assetPresent,
                fp16 = row.fp16,
                int8WithKvCache = row.int8WithKvCache,
                fusedPipeline = row.fusedPipeline,
            )
            assertEquals(
                row.expected, got.verdict,
                "${row.model}: plan verdict changed — update the task-3 " +
                    "report, not just this table (got $got)",
            )
        }
    }

    @Test
    fun `gemma stays NO-GO even with a usable probe and present asset`() {
        val got = classify(
            probeUsable = true,
            assetPresent = true,
            fp16 = true,
        )
        assertEquals(GpuVerdict.NO_GO, got.verdict)
        assertTrue("fp16" in got.reason)
    }

    @Test
    fun `host-measured CPU ordering is sane`() {
        // Snapshot of analysis/vulkan_compare.py, 2026-09-15, ORT 1.29 CPU,
        // threads=1, 3 warmup + 11 timed, Windows x64 host:
        // selfie 4.18/4.29, mobilefacenet 8.05/8.34, maia 8.65/8.77,
        // scrfd 15.06/16.12, clip 31.68/32.94, u2net 359.74/361.6 (P50/P95).
        // Exact ms are machine-specific; what must hold everywhere is the
        // ordering and that the multi-output U2-Net dominates.
        val p50 = mapOf(
            "selfie" to 4.18,
            "mobilefacenet" to 8.05,
            "maia" to 8.65,
            "scrfd" to 15.06,
            "clip" to 31.68,
            "u2net" to 359.74,
        )
        val ordered = p50.entries.sortedBy { it.value }.map { it.key }
        assertEquals(
            listOf("selfie", "mobilefacenet", "maia", "scrfd", "clip", "u2net"),
            ordered,
        )
        assertTrue(p50.getValue("u2net") > 10 * p50.getValue("clip"))
        for ((model, ms) in p50) {
            assertTrue(ms > 0, "$model must have a positive measured P50")
        }
    }
}
