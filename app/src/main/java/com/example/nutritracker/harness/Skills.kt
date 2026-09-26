package com.example.nutritracker.harness

/**
 * Skill 技能系统（教程渐进式披露）：
 * 常驻上下文只有 name + description（每个 ≤200 token）；
 * 命中后才由 load_skill 工具加载正文（粗流程，具体执行由模型自主决策）。
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String
)

class SkillRegistry(private val skills: List<Skill>) {

    fun isEmpty(): Boolean = skills.isEmpty()

    /** 渐进式披露：只把描述放进系统提示词 */
    fun descriptionsBlock(): String {
        if (skills.isEmpty()) return ""
        return buildString {
            appendLine("## 可用技能（用 load_skill 工具加载正文后遵循其流程）")
            skills.forEach { appendLine("- ${it.name}: ${it.description}") }
        }.trimEnd()
    }

    fun byName(name: String): Skill? = skills.firstOrNull { it.name == name }
}

/** 从 Markdown 文本解析 Skill（frontmatter: name/description） */
object SkillParser {
    fun parse(text: String): Skill? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("---")) return null
        val end = trimmed.indexOf("---", 3)
        if (end < 0) return null
        val frontmatter = trimmed.substring(3, end).trim()
        val body = trimmed.substring(end + 3).trim()
        var name: String? = null
        var description: String? = null
        frontmatter.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                when (key) {
                    "name" -> name = value
                    "description" -> description = value
                }
            }
        }
        name ?: return null
        return Skill(
            name = name!!,
            description = description ?: name!!,
            body = body
        )
    }
}
