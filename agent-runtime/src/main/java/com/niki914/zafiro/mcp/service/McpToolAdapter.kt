package com.niki914.zafiro.mcp.service

import android.util.Base64
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.AgentTenantContext
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.chat.agentic.stream.ParsedToolResult
import com.niki914.zafiro.settings.model.RuntimeMcpServiceConfig
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

class McpToolAdapter(
    private val builtinRegistry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
    private val tenantId: String = "mcp-host",
    /**
     * Explicit host-side allowlist. Empty by default so MCP cannot bypass the
     * model-facing capability exposure policy. Populate only after each tool
     * is routed through the same operation approval broker.
     */
    private val governedAllowedToolNames: Set<String> = emptySet(),
) {
    private companion object {
        private const val LOG_TAG = "niki914_zafiro_McpToolAdapter"
    }

    fun buildServer(config: RuntimeMcpServiceConfig): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "zafiro-mcp-server",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        val exposedTools = builtinRegistry.all().filter { tool ->
            governedAllowedToolNames.contains(tool.name) &&
                    !tool.name.startsWith("mcp__") &&
                    config.isToolExposed(tool.name)
        }

        Logger.i(LOG_TAG, "building MCP server with ${exposedTools.size} exposed tools")

        for (tool in exposedTools) {
            val schema = parseInputSchema(tool.inputSchemaJson)
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = schema,
            ) { request: CallToolRequest ->
                executeTool(tool, request)
            }
        }

        return server
    }

    private suspend fun executeTool(tool: BuiltinTool, request: CallToolRequest): CallToolResult =
        withContext(AgentTenantContext(tenantId)) {
            val argsJson = request.arguments?.toString() ?: "{}"
            // Tool arguments may contain passwords, API keys, personal data or
            // private file paths. Never write raw MCP arguments to application logs.
            Logger.i(LOG_TAG, "tool invoke start: ${tool.name}")
            val startTime = System.currentTimeMillis()

            try {
                val req = BuiltinToolRequest(name = tool.name, argumentsJson = argsJson)
                val (output, isError) = if (tool is RawJsonBuiltinTool) {
                    val raw = tool.invokeRawJson(req)
                    val parsed = ParsedToolResult.decode(raw, tool.name)
                    val isFail = parsed.status == TextToolResult.Status.Failure
                    raw to isFail
                } else {
                    val result = tool.invoke(req)
                    if (!result.ok) {
                        result.toJsonString() to true
                    } else {
                        val imageContent = tryExtractImageContent(result)
                        if (imageContent != null) {
                            val durationMs = System.currentTimeMillis() - startTime
                            Logger.i(
                                LOG_TAG,
                                "tool invoke end: ${tool.name} ok=true image=true durationMs=$durationMs"
                            )
                            return@withContext CallToolResult(
                                content = listOf(
                                    imageContent,
                                    TextContent(text = result.message.ifBlank { "Image loaded" }),
                                ),
                                isError = false,
                            )
                        }
                        result.toJsonString() to false
                    }
                }

                val jsonText = ensureJson(output)
                val durationMs = System.currentTimeMillis() - startTime
                Logger.i(
                    LOG_TAG,
                    "tool invoke end: ${tool.name} isError=$isError durationMs=$durationMs"
                )
                CallToolResult(
                    content = listOf(TextContent(text = jsonText)),
                    isError = isError,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val durationMs = System.currentTimeMillis() - startTime
                Logger.e(
                    LOG_TAG,
                    "tool invoke exception: ${tool.name}: ${e.message} durationMs=$durationMs",
                    e
                )
                val errJson = JsonObject(
                    mapOf(
                        "ok" to JsonPrimitive(false),
                        "code" to JsonPrimitive("EXCEPTION"),
                        "message" to JsonPrimitive(e.message ?: "Tool execution exception"),
                    )
                ).toString()
                CallToolResult(
                    content = listOf(TextContent(text = errJson)),
                    isError = true,
                )
            }
        }

    private fun ensureJson(output: String): String {
        val trimmed = output.trim()
        val isLikelyJson = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
        if (isLikelyJson) {
            val isValid = runCatching { Json.parseToJsonElement(trimmed) }.isSuccess
            if (isValid) {
                return trimmed
            }
        }
        return JsonObject(mapOf("raw" to JsonPrimitive(output))).toString()
    }

    private fun tryExtractImageContent(result: BuiltinToolResult): ImageContent? {
        val imageObj = result.data["image"] as? JsonObject ?: return null
        val path = (imageObj["path"] as? JsonPrimitive)?.contentOrNull ?: return null
        val mimeType = (imageObj["mime_type"] as? JsonPrimitive)?.contentOrNull ?: "image/png"
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null
        return try {
            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            ImageContent(data = base64, mimeType = mimeType)
        } catch (e: Exception) {
            Logger.w(LOG_TAG, "failed to read image file $path: ${e.message}")
            null
        }
    }

    private fun parseInputSchema(schemaJson: String?): ToolSchema {
        if (schemaJson.isNullOrBlank()) {
            return ToolSchema(properties = JsonObject(emptyMap()), required = emptyList())
        }
        return try {
            val jsonElement = Json.parseToJsonElement(schemaJson)
            val obj = jsonElement as? JsonObject ?: return ToolSchema()
            val schema = (obj["\$schema"] as? JsonPrimitive)?.contentOrNull
            val properties = obj["properties"] as? JsonObject
            val required =
                (obj["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val defs = obj["\$defs"] as? JsonObject
            ToolSchema(
                schema = schema,
                properties = properties,
                required = required,
                defs = defs,
            )
        } catch (e: Exception) {
            Logger.w(LOG_TAG, "failed to parse input schema: ${e.message}")
            ToolSchema(properties = JsonObject(emptyMap()), required = emptyList())
        }
    }
}
