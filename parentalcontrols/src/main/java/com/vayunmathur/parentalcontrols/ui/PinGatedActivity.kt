package com.vayunmathur.parentalcontrols.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.vayunmathur.parentalcontrols.auth.ParentPin

/**
 * An editor that only a parent may see.
 *
 * Settings launches editor activities directly, so each one verifies the parent PIN on every
 * resume - not just on create - because the activity can sit in the back stack while the
 * child does something else. When no PIN exists yet (fresh install), the setup screen runs
 * instead so first run always creates the gatekeeper.
 *
 * Subclasses implement [onPinVerifiedContent]: it runs only after verification (or when no
 * PIN is set, which cannot happen post-setup but is tolerated). The content is set exactly
 * once per verification; a failed or canceled challenge finishes the activity.
 */
abstract class PinGatedActivity : ComponentActivity() {

    private var verified = false

    /**
     * Whether the activity was backgrounded after content was shown.
     *
     * Guards the re-challenge in [onResume]: returning from the PIN gate itself also resumes
     * us, and that resume must not re-challenge or verification could never complete.
     */
    private var backgroundedAfterContent = false

    private val gate =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == PinGateActivity.RESULT_VERIFIED) {
                verified = true
                onPinVerifiedContent()
            } else {
                finish()
            }
        }

    private val setup =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Either way the PIN now exists or the user declined; re-run the normal gate.
            verified = false
            checkGate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkGate()
    }

    override fun onPause() {
        super.onPause()
        if (verified) backgroundedAfterContent = true
    }

    override fun onResume() {
        super.onResume()
        // Re-challenge on return: the activity may have been backgrounded while the child
        // held the device. Content from a previous verification stays visible only while
        // this resume's check passes again. The resume that follows the gate itself is
        // exempt - that is the verification completing, not a backgrounding.
        if (backgroundedAfterContent) {
            backgroundedAfterContent = false
            verified = false
            checkGate()
        }
    }

    private fun checkGate() {
        when {
            !ParentPin.get(this).isSet() ->
                setup.launch(Intent(this, PinSetupActivity::class.java))
            !verified ->
                gate.launch(Intent(this, PinGateActivity::class.java))
            else -> onPinVerifiedContent()
        }
    }

    /** Sets the activity content. Runs only with a verified (or absent) PIN. */
    protected abstract fun onPinVerifiedContent()
}
