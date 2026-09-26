package com.example.nutritracker.harness

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容客户端（DeepSeek 等）：流式 SSE 手动解析，function calling 原生协议。
 * 从 AiFoodAnalyzer 抽出的通用 HTTP 层，供 Harness 与食物识别共用。
 */
class OpenAiCompatClient(
    private val client: OkHttpClient = defaultHttpClient()
) : ModelClient {

    private val gson = Gson()

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** 把 Gson Map 请求体序列化并 POST（供食物识别等遗留路径复用） */
        fun parseContentField(messageJson: Map<*, *>): String? {
            val content = messageJson["content"] ?: return null
            return when (content) {
                is String -> content
                is List<*> -> content.filterIsInstance<Map<*, *>>()
                    .firstOrNull { it["type"] == "text" }?.get("text") as? String
                else -> null
            }
        }
    }

    // ── 请求体构建 ──

    private fun buildMessagesJson(messages: List<HarnessMessage>): List<Map<String, Any?>> =
        messages.map { msg ->
            val m = mutableMapOf<String, Any?>("role" to msg.role)
            if (msg.content != null) m["content"] = msg.content
            if (!msg.toolCallId.isNullOrBlank()) m["tool_call_id"] = msg.toolCallId
            if (!msg.name.isNullOrBlank()) m["name"] = msg.name
            if (!msg.toolCalls.isNullOrEmpty()) {
                m["tool_calls"] = msg.toolCalls.map { tc ->
                    mapOf(
                        "id" to tc.id,
                        "type" to "function",
                        "function" to mapOf("name" to tc.name, "arguments" to tc.argumentsJson)
                    )
                }
            }
            m
        }

    private fun buildToolsJson(tools: List<ToolDefinition>): List<Map<String, Any?>> =
        tools.map { def ->
            val schema = if (def.parametersJsonSchema.isBlank()) {
                mapOf<String, Any>("type" to "object", "properties" to emptyMap<String, Any>())
            } else {
                JsonParser.parseString(def.parametersJsonSchema).asJsonObject.let { gson.fromJson(it, Map::class.java) }
            }
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to def.name,
                    "description" to def.description,
                    "parameters" to schema
                )
            )
        }

    private fun buildRequestBody(
        config: HarnessConfig,
        messages: List<HarnessMessage>,
        tools: List<ToolDefinition>,
        stream: Boolean,
        temperature: Double,
        maxTokens: Int
    ): String {
        val body = linkedMapOf<String, Any?>(
            "model" to config.model,
            "messages" to buildMessagesJson(messages),
            "temperature" to temperature,
            "max_tokens" to maxTokens,
            "stream" to stream
        )
        if (tools.isNotEmpty()) {
            body["tools"] = buildToolsJson(tools)
            body["tool_choice"] = "auto"
        }
        return gson.toJson(body)
    }

    private fun request(config: HarnessConfig, bodyJson: String): Request =
        Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

    // ── 流式 ──

    override fun streamChat(
        config: HarnessConfig,
        messages: List<HarnessMessage>,
        tools: List<ToolDefinition>
    ): Flow<ModelStreamEvent> = callbackFlow {
        val bodyJson = buildRequestBody(config, messages, tools, stream = true, temperature = config.temperature, maxTokens = config.maxTokens)

        withContext(Dispatchers.IO) {
            var response: Response? = null
            try {
                response = client.newCall(request(config, bodyJson)).execute()
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(500)
                    send(ModelStreamEvent.Failed(IOException("API ${response.code}: $errBody")))
                    return@withContext
                }
                val source = response.body?.source() ?: run {
                    send(ModelStreamEvent.Failed(IOException("空响应体")))
                    return@withContext
                }
                // 累积中的工具调用分片：index -> (id, name, args)
                val toolAcc = mutableMapOf<Int, StringBuilder>()
                val toolIds = mutableMapOf<Int, String>()
                val toolNames = mutableMapOf<Int, String>()
                var finishReason: String? = null
                var receivedDone = false
                var streamFailure: Throwable? = null

                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        receivedDone = true
                        break
                    }
                    if (payload.isEmpty()) continue

                    val obj = try {
                        JsonParser.parseString(payload).asJsonObject
                    } catch (_: Exception) {
                        continue
                    }
                    if (obj.has("error")) {
                        streamFailure = IOException("API 错误: $payload")
                        break
                    }
                    val choices = obj.getAsJsonArray("choices") ?: continue
                    if (choices.size() == 0) continue
                    val choice = choices[0].asJsonObject
                    choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.let {
                        finishReason = it.asString
                    }
                    val delta = choice.getAsJsonObject("delta") ?: continue

                    delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let {
                        if (it.isNotEmpty()) send(ModelStreamEvent.ContentDelta(it))
                    }
                    delta.getAsJsonArray("tool_calls")?.forEach { tcEl ->
                        val tc = tcEl.asJsonObject
                        val idx = tc.get("index")?.asInt ?: 0
                        tc.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { toolIds[idx] = it }
                        tc.getAsJsonObject("function")?.let { fn ->
                            fn.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { toolNames[idx] = it }
                            fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { argsDelta ->
                                toolAcc.getOrPut(idx) { StringBuilder() }.append(argsDelta)
                            }
                        }
                        send(
                            ModelStreamEvent.ToolCallDelta(
                                index = idx,
                                id = tc.get("id")?.takeIf { !it.isJsonNull }?.asString,
                                name = tc.getAsJsonObject("function")?.get("name")?.takeIf { !it.isJsonNull }?.asString,
                                argsDelta = tc.getAsJsonObject("function")?.get("arguments")?.takeIf { !it.isJsonNull }?.asString
                            )
                        )
                    }
                }
                // 只有完整 SSE 终止标记才算结束；普通 EOF 可能是网络截断。
                // finish_reason=length 表示服务端因 token 上限截断，本轮不能作为完整答案结算。
                when {
                    streamFailure != null -> send(ModelStreamEvent.Failed(streamFailure!!))
                    !receivedDone -> send(ModelStreamEvent.Failed(IOException("SSE 流未收到 [DONE]，响应可能被截断")))
                    finishReason == "length" -> send(ModelStreamEvent.Failed(IOException("模型输出达到 token 上限，响应可能不完整")))
                    else -> send(ModelStreamEvent.Completed(finishReason))
                }
            } catch (e: Exception) {
                // 取消/通道关闭时 send 会抛，这里用 trySend 兜底避免掩盖取消
                trySend(ModelStreamEvent.Failed(e))
            } finally {
                response?.close()
            }
        }
        awaitClose { }
    }

    // ── 非流式 ──

    override suspend fun chat(
        config: HarnessConfig,
        messages: List<HarnessMessage>,
        tools: List<ToolDefinition>,
        temperature: Double,
        maxTokens: Int
    ): HarnessMessage = withContext(Dispatchers.IO) {
        val bodyJson = buildRequestBody(config, messages, tools, stream = false, temperature = temperature, maxTokens = maxTokens)
        var response: Response? = null
        try {
            response = client.newCall(request(config, bodyJson)).execute()
            val bodyStr = response.body?.string() ?: throw IOException("空响应体")
            if (!response.isSuccessful) throw IOException("API ${response.code}: ${bodyStr.take(300)}")
            val root = JsonParser.parseString(bodyStr).asJsonObject
            if (root.has("error")) throw IOException("API 错误: ${root.get("error").toString().take(300)}")
            val messageJson = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?: throw IOException("响应缺少 choices[0].message")
            val content = OpenAiCompatClient.parseContentField(gson.fromJson(messageJson, Map::class.java))
            val toolCalls = messageJson.getAsJsonArray("tool_calls")?.map { tcEl ->
                val tc = tcEl.asJsonObject
                val fn = tc.getAsJsonObject("function")
                ToolCall(
                    id = tc.get("id")?.takeIf { !it.isJsonNull }?.asString ?: "call_${System.nanoTime()}",
                    name = fn.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    argumentsJson = fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString ?: "{}"
                )
            }?.takeIf { it.isNotEmpty() }
            HarnessMessage(
                role = "assistant",
                content = content,
                toolCalls = toolCalls
            )
        } finally {
            response?.close()
        }
    }
}
