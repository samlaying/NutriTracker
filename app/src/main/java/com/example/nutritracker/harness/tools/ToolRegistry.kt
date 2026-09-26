package com.example.nutritracker.harness.tools

import com.example.nutritracker.harness.ToolDefinition
import com.example.nutritracker.harness.ToolResult
import com.google.gson.JsonObject

/** Harness 工具（教程 @tool 装饰器工具的 Kotlin 等价物） */
interface AgentTool {
    val name: String
    val description: String
    /** JSON Schema 对象字符串；空串表示无参数 */
    val parametersJson: String

    /** 高危写操作：执行前经 HITL 确认 */
    val requiresConfirmation: Boolean get() = false

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult

    /** 确认卡上的人类可读描述（高危写操作确认用） */
    fun describeCall(args: JsonObject): String = args.entrySet().joinToString("\n") { (k, v) ->
        "$k: ${v.toString().take(120)}"
    }

    fun definition() = ToolDefinition(name, description, parametersJson)
}

class ToolRegistry(private val tools: List<AgentTool>) {

    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    fun definitions(): List<ToolDefinition> = tools.map { it.definition() }

    fun byName(name: String): AgentTool? = byName[name]

    val size: Int get() = tools.size
}

/** 工具参数提取辅助 */
fun JsonObject.str(key: String): String? =
    this.get(key)?.takeIf { !it.isJsonNull }?.asString

fun JsonObject.intOrNull(key: String): Int? =
    this.get(key)?.takeIf { !it.isJsonNull }?.let { if (it.asJsonPrimitive.isNumber) it.asInt else it.asString.toDoubleOrNull()?.toInt() }

fun JsonObject.doubleOrNull(key: String): Double? =
    this.get(key)?.takeIf { !it.isJsonNull }?.let { if (it.asJsonPrimitive.isNumber) it.asDouble else it.asString.toDoubleOrNull() }

fun JsonObject.boolOrNull(key: String): Boolean? =
    this.get(key)?.takeIf { !it.isJsonNull }?.let { if (it.asJsonPrimitive.isBoolean) it.asBoolean else it.asString.toBooleanStrictOrNull() }
