package com.niki914.zafiro.chat.agentic

/**
 * Controls which capabilities are exposed to the model during tool resolution.
 *
 * This is an exposure gate, not a substitute for operation-level authorization.
 * The secure default exposes no executable or side-effecting tools until a
 * broker-backed approval path has been integrated and verified.
 */
data class GovernanceToolExposure(
    val allowedBuiltinNames: Set<String> = emptySet(),
    val allowCustomPython: Boolean = false,
    val allowMcpServers: Boolean = false,
) {
    fun allowsBuiltin(name: String): Boolean = name in allowedBuiltinNames

    companion object {
        /** Fail-closed baseline for builds that have not completed broker integration. */
        fun secureDefault(): GovernanceToolExposure = GovernanceToolExposure()

        /**
         * Explicit opt-in for isolated tests only. Production code must not use this
         * profile; it intentionally restores legacy exposure and provides no approval.
         */
        internal fun permissiveForTests(): GovernanceToolExposure = GovernanceToolExposure(
            allowedBuiltinNames = setOf(
                "time",
                "execute_python",
                "py_meta_tools",
                "memory",
                "notify",
                "open_uri",
                "load_skill",
                "shell",
                "ssh",
                "find_installed_apps",
                "screen_operation_accessibility",
                "screen_operation_shell",
                "screenshot",
                "view_image",
            ),
            allowCustomPython = true,
            allowMcpServers = true,
        )
    }
}
