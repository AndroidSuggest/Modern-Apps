package com.vayunmathur.emergency.ui

import android.app.Activity

/**
 * Answers Health Connect's `ACTION_SHOW_PERMISSIONS_RATIONALE`.
 *
 * Health Connect requires any app requesting medical-data permissions to declare an activity for
 * this action, and links to it from its own permission dialog. The emergency app's use is
 * explained inline on the edit screen (the "Import from Health Connect" row), so there is nothing
 * to add here - but the component must exist or the permission request is rejected. Same bare
 * activity the health app ships for the same reason.
 */
class PermissionsRationaleActivity : Activity()
