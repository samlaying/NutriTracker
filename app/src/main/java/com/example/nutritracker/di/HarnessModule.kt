package com.example.nutritracker.di

import android.content.Context
import com.example.nutritracker.harness.AgentHarness
import com.example.nutritracker.harness.ContextCompressor
import com.example.nutritracker.harness.HarnessLimits
import com.example.nutritracker.harness.MemoryWriter
import com.example.nutritracker.harness.ModelClient
import com.example.nutritracker.harness.ModelContextCompressor
import com.example.nutritracker.harness.OpenAiCompatClient
import com.example.nutritracker.harness.Skill
import com.example.nutritracker.harness.SkillParser
import com.example.nutritracker.harness.SkillRegistry
import com.example.nutritracker.harness.SubAgentRunner
import com.example.nutritracker.harness.tools.AgentTool
import com.example.nutritracker.harness.tools.GetRecentRecordsTool
import com.example.nutritracker.harness.tools.GetTodaySummaryTool
import com.example.nutritracker.harness.tools.GetTrainingPlanTool
import com.example.nutritracker.harness.tools.GetWeightTrendTool
import com.example.nutritracker.harness.tools.LoadSkillTool
import com.example.nutritracker.harness.tools.LogActivityTool
import com.example.nutritracker.harness.tools.LogFoodTool
import com.example.nutritracker.harness.tools.LogWaterTool
import com.example.nutritracker.harness.tools.LogWeightTool
import com.example.nutritracker.harness.tools.CancelTaskTool
import com.example.nutritracker.harness.tools.CreateTrainingPlanTool
import com.example.nutritracker.harness.tools.DelegateTaskTool
import com.example.nutritracker.harness.tools.OpenScreenTool
import com.example.nutritracker.harness.tools.QueryTaskTool
import com.example.nutritracker.harness.tools.ReadFileTool
import com.example.nutritracker.harness.tools.SearchSourcesTool
import com.example.nutritracker.harness.tools.StartTaskTool
import com.example.nutritracker.harness.tools.ToolRegistry
import com.example.nutritracker.harness.tools.UpdateSessionStatusTool
import com.example.nutritracker.harness.tools.UpdateProfileTool
import com.example.nutritracker.harness.tools.WriteFileTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** /reports 文件后端：Harness 的执行边界（路径白名单在工具层校验） */
class ReportsFileBackend(private val root: File) : com.example.nutritracker.harness.tools.HarnessFileBackend {
    init {
        root.mkdirs()
    }

    private fun resolve(path: String): File {
        val clean = path.removePrefix("/").removeSuffix("/")
        val f = File(root, clean)
        if (!f.canonicalPath.startsWith(root.canonicalPath)) {
            throw SecurityException("路径越界: $path")
        }
        return f
    }

    override suspend fun write(path: String, content: String): String {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f.absolutePath
    }

    override suspend fun read(path: String): String = resolve(path).readText()

    override suspend fun list(prefix: String): List<String> {
        val dir = resolve(prefix)
        return dir.listFiles()?.map { it.absolutePath } ?: emptyList()
    }
}

@Qualifier annotation class AgentMdText

@Module
@InstallIn(SingletonComponent::class)
object HarnessModule {

    @Provides
    @Singleton
    fun provideModelClient(): ModelClient = OpenAiCompatClient()

    @Provides
    @Singleton
    fun provideFileBackend(@ApplicationContext ctx: Context): com.example.nutritracker.harness.tools.HarnessFileBackend =
        ReportsFileBackend(File(ctx.filesDir, "reports"))

    /** AGENT.md 从 assets/harness/AGENT.md 加载（Claude Code 式惯例：短提示词 + 独立规则文件） */
    @Provides
    @Singleton
    @AgentMdText
    fun provideAgentMd(@ApplicationContext ctx: Context): String = try {
        ctx.assets.open("harness/AGENT.md").bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        "" // 资产缺失时降级为仅角色+安全规则
    }

    /** 技能注册表：assets/harness/skills 目录下的 .md 文件，渐进式披露 */
    @Provides
    @Singleton
    fun provideSkillRegistry(@ApplicationContext ctx: Context): SkillRegistry {
        val skills = mutableListOf<Skill>()
        try {
            val files = ctx.assets.list("harness/skills") ?: emptyArray()
            files.filter { it.endsWith(".md") }.forEach { fileName ->
                runCatching {
                    val text = ctx.assets.open("harness/skills/$fileName").bufferedReader().use { it.readText() }
                    SkillParser.parse(text)
                }.getOrNull()?.let { skills += it }
            }
        } catch (_: Exception) {
        }
        return SkillRegistry(skills)
    }

    @Provides
    @Singleton
    fun provideToolRegistry(): ToolRegistry = ToolRegistry(
        listOf<AgentTool>(
            // 读
            GetTodaySummaryTool(),
            GetRecentRecordsTool(),
            GetWeightTrendTool(),
            GetTrainingPlanTool(),
            SearchSourcesTool(),
            LoadSkillTool(),
            // 写（直接落库 + 可撤销卡片）
            LogFoodTool(),
            LogWaterTool(),
            LogWeightTool(),
            LogActivityTool(),
            // 训练
            CreateTrainingPlanTool(),
            UpdateSessionStatusTool(),
            // 资料/导航/系统
            UpdateProfileTool(),
            OpenScreenTool(),
            WriteFileTool(),
            ReadFileTool(),
            DelegateTaskTool(),
            StartTaskTool(),
            QueryTaskTool(),
            CancelTaskTool()
        )
    )

    @Provides
    @Singleton
    fun provideContextCompressor(modelClient: ModelClient): ContextCompressor =
        ModelContextCompressor(modelClient)

    @Provides
    @Singleton
    fun provideSubAgentRunner(modelClient: ModelClient, registry: ToolRegistry): SubAgentRunner =
        SubAgentRunner(modelClient, registry)

    @Provides
    @Singleton
    fun provideSystemPromptBuilder(@AgentMdText agentMd: String): com.example.nutritracker.harness.SystemPromptBuilder =
        com.example.nutritracker.harness.SystemPromptBuilder(agentMd)

    @Provides
    @Singleton
    fun provideMemoryWriter(modelClient: ModelClient): MemoryWriter = MemoryWriter(modelClient)

    @Provides
    @Singleton
    fun provideAgentHarness(
        modelClient: ModelClient,
        registry: ToolRegistry,
        compressor: ContextCompressor
    ): AgentHarness = AgentHarness(
        modelClient = modelClient,
        toolRegistry = registry,
        limits = HarnessLimits(),
        compressor = compressor
    )
}
