package com.niki914.zafiro.chat.agentic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OperationApprovalVaultTest {
    private var now = 1_000L
    private val vault = OperationApprovalVault(
        clock = { now },
        tokenLifetimeMillis = 500L,
    )

    private fun request(
        session: String = "session-a",
        args: String = "{\"path\":\"/tmp/a\"}",
        policy: Long = 4L,
    ) = OperationApprovalVault.ApprovalRequest(
        sessionId = session,
        requesterId = "agent-a",
        capability = "file.write",
        target = "/tmp/a",
        canonicalArguments = args,
        expectedEffects = "Write one file",
        risk = OperationApprovalVault.Risk.HIGH,
        policyVersion = policy,
    )

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroTokenLifetime() {
        OperationApprovalVault(clock = { now }, tokenLifetimeMillis = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedOutstandingTokenLimit() {
        OperationApprovalVault(clock = { now }, maxOutstandingTokens = 257)
    }

    @Test
    fun approvalIsBoundToExactOperationAndIsOneUse() {
        val original = request()
        val token = vault.approveAfterHumanConfirmation(original, "human-1")!!
        assertNotNull(vault.consume(token, original, "session-a", 4L))
        assertNull(vault.consume(token, original, "session-a", 4L))
    }

    @Test
    fun utf8ArgumentsAreBoundToExactOperation() {
        val original = request(args = """{"path":"/tmp/café/文件"}""")
        val token = vault.approveAfterHumanConfirmation(original, "human-1")!!

        assertNotNull(vault.consume(token, original, "session-a", 4L))
        assertNull(vault.consume(token, original, "session-a", 4L))
    }

    @Test
    fun changedArgumentsBurnTheToken() {
        val token = vault.approveAfterHumanConfirmation(request(), "human-1")!!
        assertNull(vault.consume(token, request(args = "{\"path\":\"/tmp/b\"}"), "session-a", 4L))
        assertNull(vault.consume(token, request(), "session-a", 4L))
    }

    @Test
    fun wrongSessionAndPolicyAreRejected() {
        val wrongSessionToken = vault.approveAfterHumanConfirmation(request(), "human-1")!!
        assertNull(vault.consume(wrongSessionToken, request(), "session-b", 4L))

        val wrongPolicyToken = vault.approveAfterHumanConfirmation(request(), "human-1")!!
        assertNull(vault.consume(wrongPolicyToken, request(), "session-a", 5L))
    }

    @Test
    fun expiryAndRevocationAreEnforced() {
        val expired = vault.approveAfterHumanConfirmation(request(), "human-1")!!
        now += 500L
        assertNull(vault.consume(expired, request(), "session-a", 4L))

        now += 1L
        val revoked = vault.approveAfterHumanConfirmation(request(), "human-1")!!
        vault.revokeSession("session-a")
        assertNull(vault.consume(revoked, request(), "session-a", 4L))
        assertEquals(0, vault.outstandingCount())
    }
}
