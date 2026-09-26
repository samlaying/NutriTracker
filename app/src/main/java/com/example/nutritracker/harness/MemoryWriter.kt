package com.example.nutritracker.harness

import com.example.nutritracker.data.repository.MemoryRepository
import com.google.gson.JsonParser

/**
 * 记忆回写中间件（教程 MemoryUpdateMiddleware）：
 * 每轮对话后自动提取稳定的长期偏好并持久化——不依赖用户明说；
 * 摘要掉了也没关系，下一轮 before-agent 注入时会回来。
 */
class MemoryWriter(private val modelClient: ModelClient) {

    companion object {
        private val CATEGORIES = setOf("偏好", "伤病史", "器械条件", "口味", "作息", "目标背景")
    }

    suspend fun afterTurn(
        config: HarnessConfig,
        memoryRepo: MemoryRepository,
        userText: String?,
        reply: String?
    ) {
        val user = userText?.takeIf { it.length >= 6 } ?: return
        val existing = memoryRepo.getAll()
        val existingText = existing.joinToString("\n") { "- [${it.category}] ${it.content} (id=${it.id})" }
            .ifBlank { "（空）" }

        val prompt = """
            你是记忆管理器。根据最新一轮对话，更新用户的长期偏好记忆。

            现有记忆：
            $existingText

            最新对话：
            用户：${user.take(600)}
            教练：${(reply ?: "").take(600)}

            规则：
            - 只提取稳定的长期信息（训练偏好、伤病、器械条件、饮食口味、作息、目标背景）
            - 不记录一次性事实："今天吃了鸡胸肉"不记；"不吃辣""只有哑铃"要记
            - 与现有记忆重复的内容不要重复添加
            - remove_ids 只移除与新增信息明确矛盾的旧记忆

            只输出 JSON，不要其他文字：
            {"add": [{"category": "偏好|伤病史|器械条件|口味|作息|目标背景", "content": "..."}], "remove_ids": [数字id]}
            没有变化输出 {"add": [], "remove_ids": []}
        """.trimIndent()

        val summaryConfig = config.copy(
            model = config.summaryModel?.takeIf { it.isNotBlank() } ?: config.model,
            maxTokens = 600
        )
        val content = try {
            modelClient.chat(summaryConfig, listOf(HarnessMessage.user(prompt)), temperature = 0.1)
                .content ?: return
        } catch (_: Exception) {
            return
        }

        // 解析（剥掉可能的 ``` 围栏）
        val jsonText = content.replace("```json", "").replace("```", "").trim()
        val start = jsonText.indexOf('{')
        val end = jsonText.lastIndexOf('}')
        if (start < 0 || end <= start) return
        val obj = try {
            JsonParser.parseString(jsonText.substring(start, end + 1)).asJsonObject
        } catch (_: Exception) {
            return
        }

        // 移除矛盾旧记忆
        obj.getAsJsonArray("remove_ids")?.forEach { el ->
            val id = el.asLong
            existing.firstOrNull { it.id == id }?.let { memoryRepo.deleteById(it.id) }
        }

        // 新增（每轮最多 3 条，按内容去重）
        obj.getAsJsonArray("add")?.takeIf { it.size() > 0 }?.let { arr ->
            val existingContents = existing.map { it.content }.toSet()
            arr.take(3).forEach { el ->
                val o = el.asJsonObject
                val category = o.get("category")?.takeIf { !it.isJsonNull }?.asString ?: "偏好"
                val newContent = o.get("content")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                if (category in CATEGORIES && newContent.isNotBlank() && newContent !in existingContents) {
                    memoryRepo.add(category, newContent)
                }
            }
        }
    }
}
