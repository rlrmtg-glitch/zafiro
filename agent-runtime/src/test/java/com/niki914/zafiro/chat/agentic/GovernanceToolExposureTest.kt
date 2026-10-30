package com.niki914.zafiro.chat.agentic

import com.niki914.zafiro.chat.LocalTool
import com.niki914.zafiro.chat.McpServerDefinition
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernanceToolExposureTest {
    @Test
    fun secureDefaultExposesNoBuiltinCustomPythonOrMcpCapabilities() {
        val manager = ToolManager(
            builtinToolRegistry = BuiltinToolRegistry(
                listOf(FakeTool("shell"), FakeTool("find_installed_apps"))
            )
        )

        val resolved = manager.resolve(
            customPyTools = listOf(
                RuntimeCustomPyTool(
                    name = "py_probe",
                    description = "probe",
                    code = "def main(): pass",
                    schemaJson = "{}",
                )
            ),
            mcpServers = listOf(RuntimeMcpServer(name = "remote", url = "http://127.0.0.1:9999/mcp")),
            builtinSettings = listOf(
                RuntimeBuiltinToolSetting(name = "shell", description = "shell", enabled = true),
                RuntimeBuiltinToolSetting(name = "find_installed_apps", description = "apps", enabled = true),
            ),
        )

        assertTrue(resolved.builtinTools.isEmpty())
        assertTrue(resolved.customPyTools.isEmpty())
        assertTrue(resolved.mcpServers.isEmpty())
    }

    private class FakeTool(override val name: String) : BuiltinTool() {
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            BuiltinToolResult.success("test")
    }
}
