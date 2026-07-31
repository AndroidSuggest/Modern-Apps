package org.schabi.newpipe.extractor.services.youtube.sabr

/**
 * Bundled protocol behavior used when no verified JavaScript policy is active.
 */
class BuiltinSabrSessionPolicy : SabrSessionPolicy {

    override fun evaluate(state: SabrSessionPolicy.State, event: SabrSessionPolicy.Event): SabrSessionPolicy.Result {
        if (event is SabrSessionPolicy.RequestEvent) {
            return SabrSessionPolicy.Result.request(
                state,
                if (state.requestNumber == 0)
                    SabrSessionPolicy.ActionType.SEND_INITIAL_REQUEST
                else
                    SabrSessionPolicy.ActionType.SEND_FOLLOW_UP_REQUEST,
                event.getProposedBody()
            )
        }
        val control = event as SabrSessionPolicy.ControlResponseEvent
        val response = control.getResponse()
        val actions = mutableListOf<SabrSessionPolicy.Action>()
        var next = state
        actions.add(SabrSessionPolicy.Action(SabrSessionPolicy.ActionType.APPLY_RESPONSE_STATE))
        val redirectUrl = response.getRedirectUrl()
        if (!redirectUrl.isNullOrEmpty()) {
            actions.add(SabrSessionPolicy.Action(SabrSessionPolicy.ActionType.APPLY_REDIRECT))
            next = SabrSessionPolicy.State(
                state.requestNumber,
                state.redirectCount + 1,
                state.poTokenRefreshes,
                state.getReloads()
            )
        }
        if (response.getSabrErrorDetails() != null) {
            actions.add(SabrSessionPolicy.Action(SabrSessionPolicy.ActionType.FAIL_SABR_ERROR))
            return SabrSessionPolicy.Result.control(
                next,
                actions,
                SabrSessionPolicy.ControlDecision(
                    0,
                    redirectUrl,
                    response.getSabrErrorDetails()!!.summarize()
                ),
                SabrResponseStatePatch.builtin(response)
            )
        }
        if (response.isReloadRequested()) {
            actions.add(SabrSessionPolicy.Action(SabrSessionPolicy.ActionType.TRY_RELOAD))
            return SabrSessionPolicy.Result.control(
                next,
                actions,
                SabrSessionPolicy.ControlDecision(0, redirectUrl, null),
                SabrResponseStatePatch.builtin(response)
            )
        }
        if (response.isProtectionBoundaryNoMediaResponse()) {
            actions.add(
                SabrSessionPolicy.Action(
                    if (control.mode == SabrSessionPolicy.ControlMode.FETCH_SEGMENT)
                        SabrSessionPolicy.ActionType.REQUIRE_PO_TOKEN
                    else
                        SabrSessionPolicy.ActionType.REFRESH_PO_TOKEN
                )
            )
        }
        if (control.mode == SabrSessionPolicy.ControlMode.PUMP && control.segmentCount > 0) {
            next = state.resetRecoveryBudgets()
            actions.add(SabrSessionPolicy.Action(SabrSessionPolicy.ActionType.RESET_RECOVERY_BUDGETS))
        }
        val backoff = response.getBackoffTimeMs().coerceAtLeast(0)
        if (backoff > 0) {
            actions.add(
                SabrSessionPolicy.Action(
                    if (control.shouldHonorBackoff())
                        SabrSessionPolicy.ActionType.SLEEP_BACKOFF
                    else
                        SabrSessionPolicy.ActionType.DEFER_BACKOFF
                )
            )
        } else if (!control.shouldHonorBackoff()) {
            actions.add(SabrSessionPolicy.Action(SabrSessionPolicy.ActionType.CLEAR_DEMAND_BACKOFF))
        }
        actions.add(
            SabrSessionPolicy.Action(
                if (control.mode == SabrSessionPolicy.ControlMode.FETCH_SEGMENT
                    && response.isProtectionBoundaryNoMediaResponse()
                )
                    SabrSessionPolicy.ActionType.RETRY
                else
                    SabrSessionPolicy.ActionType.CONTINUE
            )
        )
        return SabrSessionPolicy.Result.control(
            next,
            actions,
            SabrSessionPolicy.ControlDecision(backoff, redirectUrl, null),
            SabrResponseStatePatch.builtin(response)
        )
    }
}
