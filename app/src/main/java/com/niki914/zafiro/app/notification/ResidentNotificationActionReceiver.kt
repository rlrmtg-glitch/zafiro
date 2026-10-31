package com.niki914.zafiro.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.service.requireService

/**
 * Handles actions created by Zafiro's own immutable resident-notification PendingIntents.
 *
 * This receiver is deliberately non-exported. Notification actions must not be
 * accepted as start intents by AgentRuntimeService because that service is
 * exported for authenticated host Binder IPC.
 */
class ResidentNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ResidentNotificationBuilder.ACTION_STOP -> stopAgent()
            ResidentNotificationBuilder.ACTION_APPROVE ->
                ResidentNotificationManager.resolveApproval(ApprovalDecision.Allow)
            ResidentNotificationBuilder.ACTION_DECLINE ->
                ResidentNotificationManager.resolveApproval(ApprovalDecision.Deny)
        }
    }

    private fun stopAgent() {
        runCatching { requireService<AgentControl>().stop() }
            .onFailure { error ->
                Logger.e(TAG, "Resident notification stop action failed", error)
            }
    }

    private companion object {
        const val TAG = "niki914_zafiro_ResidentNotificationActionReceiver"
    }
}
