package com.niki914.zafiro.chat.agentic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One-use approval vault for consequential operations.
 *
 * The UI/controller must call [approveAfterHumanConfirmation] only after the
 * human has reviewed and explicitly confirmed the exact [ApprovalRequest].
 * This class does not display UI and cannot establish that confirmation itself
 * was genuine; callers must keep that boundary outside model/tool control.
 *
 * Tokens are session-, policy-, actor-, capability- and argument-bound.
 * A consume attempt burns the token even when validation fails.
 */
class OperationApprovalVault(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val tokenLifetimeMillis: Long = DEFAULT_TOKEN_LIFETIME_MILLIS,
    private val maxOutstandingTokens: Int = DEFAULT_MAX_OUTSTANDING_TOKENS,
) {
    data class ApprovalRequest(
        val sessionId: String,
        val requesterId: String,
        val capability: String,
        val target: String,
        /** Canonical, fully resolved arguments; do not pass raw model JSON. */
        val canonicalArguments: String,
        val expectedEffects: String,
        val risk: Risk,
        val privilege: String = "user",
        val networkDestination: String = "",
        val reversible: Boolean = false,
        val policyVersion: Long,
    )

    enum class Risk { LOW, MEDIUM, HIGH, CRITICAL }

    data class ApprovedOperation internal constructor(
        val requestDigest: String,
        val reviewerId: String,
        val approvedAtMillis: Long,
        val expiresAtMillis: Long,
    )

    private data class StoredToken(
        val tokenHash: String,
        val requestDigest: String,
        val sessionId: String,
        val policyVersion: Long,
        val reviewerId: String,
        val expiresAtMillis: Long,
    )

    private val tokens = LinkedHashMap<String, StoredToken>()

    init {
        require(tokenLifetimeMillis in 1L..MAX_TOKEN_LIFETIME_MILLIS) {
            "Approval token lifetime must be between 1 ms and 5 minutes"
        }
        require(maxOutstandingTokens in 1..MAX_OUTSTANDING_TOKENS) {
            "Outstanding approval token limit must be between 1 and 256"
        }
    }

    /**
     * Call only from a trusted human-confirmation handler, never from a model
     * tool, MCP request, background agent, or imported skill.
     */
    @Synchronized
    fun approveAfterHumanConfirmation(
        request: ApprovalRequest,
        reviewerId: String,
    ): String? {
        if (!request.isValid() || reviewerId.isBlank()) return null
        purgeExpired()
        if (tokens.size >= maxOutstandingTokens) return null

        val raw = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val now = clock()
        val expires = runCatching { Math.addExact(now, tokenLifetimeMillis) }.getOrNull()
            ?: return null
        val tokenHash = sha256(token)
        tokens[tokenHash] = StoredToken(
            tokenHash = tokenHash,
            requestDigest = digest(request),
            sessionId = request.sessionId,
            policyVersion = request.policyVersion,
            reviewerId = reviewerId,
            expiresAtMillis = expires,
        )
        return token
    }

    /**
     * Atomically consumes a token. Any attempted use burns it, including a
     * mismatched operation, stale policy, expired token, or wrong session.
     */
    @Synchronized
    fun consume(
        token: String,
        actualRequest: ApprovalRequest,
        activeSessionId: String,
        activePolicyVersion: Long,
    ): ApprovedOperation? {
        if (token.isBlank()) return null
        val stored = tokens.remove(sha256(token)) ?: return null
        val now = clock()
        if (stored.expiresAtMillis <= now) return null
        if (!actualRequest.isValid()) return null
        if (stored.sessionId != activeSessionId || actualRequest.sessionId != activeSessionId) return null
        if (stored.policyVersion != activePolicyVersion ||
            actualRequest.policyVersion != activePolicyVersion) return null
        val actualDigest = digest(actualRequest)
        if (!constantTimeEquals(stored.requestDigest, actualDigest)) return null

        return ApprovedOperation(
            requestDigest = stored.requestDigest,
            reviewerId = stored.reviewerId,
            approvedAtMillis = now,
            expiresAtMillis = stored.expiresAtMillis,
        )
    }

    @Synchronized
    fun revokeSession(sessionId: String) {
        if (sessionId.isBlank()) return
        tokens.entries.removeAll { it.value.sessionId == sessionId }
    }

    @Synchronized
    fun revokeAll() {
        tokens.clear()
    }

    @Synchronized
    fun outstandingCount(): Int {
        purgeExpired()
        return tokens.size
    }

    private fun purgeExpired() {
        val now = clock()
        tokens.entries.removeAll { it.value.expiresAtMillis <= now }
    }

    private fun ApprovalRequest.isValid(): Boolean =
        sessionId.isNotBlank() &&
            requesterId.isNotBlank() &&
            capability.isNotBlank() &&
            target.isNotBlank() &&
            canonicalArguments.isNotBlank() &&
            expectedEffects.isNotBlank() &&
            policyVersion >= 0

    private fun digest(request: ApprovalRequest): String {
        val fields = listOf(
            request.sessionId,
            request.requesterId,
            request.capability,
            request.target,
            request.canonicalArguments,
            request.expectedEffects,
            request.risk.name,
            request.privilege,
            request.networkDestination,
            request.reversible.toString(),
            request.policyVersion.toString(),
        )
        val bytes = fields.joinToString(separator = "") { field ->
            val encodedSize = field.toByteArray(StandardCharsets.UTF_8).size
            "$encodedSize:$field"
        }.toByteArray(StandardCharsets.UTF_8)
        return sha256(bytes)
    }

    private fun sha256(value: String): String =
        sha256(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") {
            "%02x".format(it)
        }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(StandardCharsets.US_ASCII),
            right.toByteArray(StandardCharsets.US_ASCII),
        )

    companion object {
        private const val TOKEN_BYTES = 32
        private const val MAX_TOKEN_LIFETIME_MILLIS = 5 * 60_000L
        private const val MAX_OUTSTANDING_TOKENS = 256
        const val DEFAULT_TOKEN_LIFETIME_MILLIS = 60_000L
        const val DEFAULT_MAX_OUTSTANDING_TOKENS = 64
    }
}
