package com.example.nutritracker.harness.tools

import com.example.nutritracker.data.entity.Intake
import com.example.nutritracker.harness.MissingField
import com.example.nutritracker.harness.PendingInteraction
import com.example.nutritracker.harness.ToolResult
import com.example.nutritracker.data.entity.IntakeType
import com.example.nutritracker.data.entity.Meal
import com.example.nutritracker.data.entity.MealSource
import com.example.nutritracker.data.entity.UserActivityEntity
import com.example.nutritracker.data.entity.WaterIntake
import com.example.nutritracker.data.entity.WeightLog
import com.example.nutritracker.util.MetCalc
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

// ── log_food ─────────────────────────────────────────────────────────────────

class LogFoodTool : AgentTool {
    override val name = "log_food"
    override val description =
        "记录一餐饮食。必须提供每 100g 的营养估算值（不确定时用保守估计并告知用户）。写入后立即生效，用户可在卡片上删除或编辑。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "name": {"type": "string", "description": "食物名"},
            "grams": {"type": "number", "description": "重量（克）"},
            "intake_type": {"type": "string", "enum": ["breakfast", "lunch", "dinner", "snack"]},
            "kcal_per_100g": {"type": "number"},
            "carbs_per_100g": {"type": "number"},
            "fat_per_100g": {"type": "number"},
            "protein_per_100g": {"type": "number"},
            "date": {"type": "string", "description": "YYYY-MM-DD 或 今天/昨天，默认今天"}
        },
        "required": ["name", "grams", "intake_type", "kcal_per_100g", "carbs_per_100g", "fat_per_100g", "protein_per_100g"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.str("name")
        val grams = args.doubleOrNull("grams")
        val typeStr = args.str("intake_type")
        val kcal100 = args.doubleOrNull("kcal_per_100g")
        val carbs100 = args.doubleOrNull("carbs_per_100g")
        val fat100 = args.doubleOrNull("fat_per_100g")
        val protein100 = args.doubleOrNull("protein_per_100g")
        if (name.isNullOrBlank() || grams == null || grams <= 0 || typeStr == null ||
            kcal100 == null || carbs100 == null || fat100 == null || protein100 == null
        ) {
            // 缺数据 → 表单式补充（HITL 第一级）
            return ToolResult(
                summary = "参数不足",
                interrupt = PendingInteraction.MissingData(
                    question = "记录\"${name ?: "这餐"}\"还缺一些信息：",
                    collectedSummary = argsPreviewOf(args),
                    fields = listOf(
                        MissingField("grams", "重量（克）", listOf("100", "150", "200", "250")),
                        MissingField("kcal_per_100g", "热量 kcal/100g", listOf("按常见值估算")),
                        MissingField("intake_type", "餐次", listOf("breakfast", "lunch", "dinner", "snack"))
                    )
                )
            )
        }

        val intakeType = when (typeStr.lowercase()) {
            "breakfast" -> IntakeType.BREAKFAST
            "lunch" -> IntakeType.LUNCH
            "dinner" -> IntakeType.DINNER
            else -> IntakeType.SNACK
        }
        val date = parseFlexibleDate(args.str("date"), ctx.today) ?: ctx.today

        // 写入
        val meal = Meal(
            name = name,
            source = MealSource.AGENT_CHAT,
            energyKcal100 = kcal100,
            carbohydrates100 = carbs100,
            fat100 = fat100,
            proteins100 = protein100
        )
        val mealId = ctx.mealRepo.upsert(meal)
        val now = LocalDateTime.now()
        ctx.intakeRepo.upsert(
            Intake(mealId = mealId, intakeType = intakeType, amount = grams, dateTime = now)
        )
        ctx.trackedDayRepo.ensureDay(date, 0.0, 0.0, 0.0, 0.0)
        ctx.trackedDayRepo.addCalories(
            date,
            kcal100 * grams / 100.0,
            carbs100 * grams / 100.0,
            fat100 * grams / 100.0,
            protein100 * grams / 100.0
        )

        // 回源验证：读回真实落库值
        val savedMeal = ctx.mealRepo.getById(mealId)
        val actualKcal = (savedMeal?.energyKcal100 ?: kcal100) * grams / 100.0
        val payload = Gson().toJson(
            mapOf(
                "name" to name, "grams" to grams, "meal_type" to typeStr,
                "kcal" to (actualKcal * 10).roundToInt() / 10.0, "meal_id" to mealId, "date" to date.toString()
            )
        )
        return ToolResult.ok(
            summary = "已记录：$name ${grams.roundToInt()}g（${intakeTypeLabel(intakeType)}），热量 ≈${actualKcal.roundToInt()} kcal（每100g: ${kcal100.f1()}kcal/C${carbs100.f1()}/F${fat100.f1()}/P${protein100.f1()}），已写回数据库并计入当日总览。",
            cardType = "meal_logged",
            payloadJson = payload
        )
    }

    private fun argsPreviewOf(args: JsonObject): String =
        args.entrySet().filter { it.value != null && !it.value.isJsonNull }
            .joinToString("；") { "${it.key}=${it.value.toString().take(30)}" }
            .ifBlank { "无" }
}

// ── log_water ────────────────────────────────────────────────────────────────

class LogWaterTool : AgentTool {
    override val name = "log_water"
    override val description = "记录饮水（毫升），写入后返回当日累计。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "amount_ml": {"type": "integer", "description": "饮水量（毫升）"},
            "date": {"type": "string", "description": "默认今天"}
        },
        "required": ["amount_ml"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val amount = args.intOrNull("amount_ml")
            ?: return ToolResult.failure("缺少 amount_ml")
        if (amount <= 0 || amount > 3000) return ToolResult.failure("amount_ml 应在 1~3000 之间，收到 $amount")
        val date = parseFlexibleDate(args.str("date"), ctx.today) ?: ctx.today
        val waterId = ctx.waterRepo.upsert(WaterIntake(amountMl = amount, dateTime = LocalDateTime.now()))
        val total = ctx.waterRepo.getTotalMlByLogicalDay(date, ctx.dayBoundaryOffset)
        val goal = ctx.settingsRepo.waterGoalMl.first()
        val payload = Gson().toJson(mapOf("amount_ml" to amount, "total_ml" to total, "goal_ml" to goal, "water_id" to waterId))
        return ToolResult.ok(
            "已记录饮水 ${amount}ml，当日累计 $total/$goal ml。",
            cardType = "water_logged",
            payloadJson = payload
        )
    }
}

// ── log_weight ───────────────────────────────────────────────────────────────

class LogWeightTool : AgentTool {
    override val name = "log_weight"
    override val description =
        "记录当日体重（kg），同时更新个人资料里的当前体重（影响热量目标计算）。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "weight_kg": {"type": "number", "description": "体重（公斤）"}
        },
        "required": ["weight_kg"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val weight = args.doubleOrNull("weight_kg")
            ?: return ToolResult.failure("缺少 weight_kg")
        if (weight < 25 || weight > 350) return ToolResult.failure("weight_kg 不在合理范围（25~350），收到 $weight")
        val user = ctx.userRepo.getUser()
        val old = user?.weightKg
        ctx.weightRepo.upsert(WeightLog(date = ctx.today, weightKg = weight))
        if (user != null && old != weight) {
            ctx.userRepo.upsert(user.copy(weightKg = weight))
        }
        val delta = old?.let { weight - it }
        val payload = Gson().toJson(
            mapOf("weight_kg" to weight, "previous_kg" to old, "date" to ctx.today.toString())
        )
        return ToolResult.ok(
            "已记录体重 ${weight.f1()}kg" + (delta?.let { "，较上次资料体重${if (it >= 0) "+" else ""}${it.f1()}kg" } ?: "") + "，热量目标将随之更新。",
            cardType = "weight_logged",
            payloadJson = payload
        )
    }
}

// ── log_activity ─────────────────────────────────────────────────────────────

class LogActivityTool : AgentTool {
    override val name = "log_activity"
    override val description =
        "记录一次体育活动（名称、时长、MET）。MET 缺省时按名称匹配内置目录（2024 Adult Compendium），匹配不到可给近似 MET 并说明。写入后返回消耗与当日累计。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "name": {"type": "string", "description": "活动名，如 慢跑/力量训练/骑行"},
            "duration_minutes": {"type": "number"},
            "mets": {"type": "number", "description": "可选；不提供则按名称匹配目录或用常见值"}
        },
        "required": ["name", "duration_minutes"]
    }"""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.str("name")
        val minutes = args.doubleOrNull("duration_minutes")
        if (name.isNullOrBlank() || minutes == null || minutes <= 0 || minutes > 600) {
            return ToolResult.failure("需要 name 和 duration_minutes（1~600 分钟）")
        }
        val user = ctx.userRepo.getUser()
            ?: return ToolResult.failure("用户尚未完成引导，无法计算消耗")
        val mets = args.doubleOrNull("mets")
            ?: matchMets(name)
            ?: return ToolResult.failure("无法确定\"$name\"的 MET 值。请提供 mets 参数（可在常见值中估计，如快走4.3、慢跑7、力量训练5）。")

        val burned = MetCalc.getBurnedKcal(user.weightKg, mets, minutes)
        val activityId = ctx.activityRepo.upsert(
            UserActivityEntity(
                name = name, mets = mets, durationMinutes = minutes,
                burnedKcal = burned, dateTime = LocalDateTime.now(), isCustom = false
            )
        )
        // 运动消耗计入当日目标（HomeViewModel 同口径：TDEE + 活动消耗）
        val burnTotal = ctx.activityRepo.getTotalBurnedByLogicalDay(ctx.today, ctx.dayBoundaryOffset)
        val payload = Gson().toJson(
            mapOf(
                "name" to name, "minutes" to minutes, "mets" to mets,
                "burned_kcal" to (burned * 10).roundToInt() / 10.0, "date" to ctx.today.toString(),
                "activity_id" to activityId
            )
        )
        return ToolResult.ok(
            "已记录：$name ${minutes.roundToInt()} 分钟（MET $mets），消耗 ≈${burned.roundToInt()} kcal；今日活动总消耗 ${burnTotal.roundToInt()} kcal，热量目标已随之上调。",
            cardType = "activity_logged",
            payloadJson = payload
        )
    }

    private fun matchMets(name: String): Double? {
        val n = name.lowercase()
        return when {
            n.contains("走") && (n.contains("快") || n.contains(" brisk")) -> 4.3
            n.contains("散步") || n.contains("慢走") -> 2.8
            n.contains("跑") && n.contains("慢") -> 7.0
            n.contains("跑") -> 9.8
            n.contains("力量") || n.contains("器械") || n.contains("撸铁") || n.contains("举重") -> 5.0
            n.contains("骑行") || n.contains("单车") || n.contains("bike") || n.contains("cycl") -> 7.5
            n.contains("游泳") || n.contains("swim") -> 8.3
            n.contains("瑜伽") || n.contains("yoga") -> 2.5
            n.contains("拉伸") || n.contains("stretch") -> 2.3
            n.contains("跳绳") || n.contains("jump") -> 12.3
            n.contains("椭圆") -> 5.0
            n.contains("爬山") || n.contains("徒步") || n.contains("hik") -> 6.0
            n.contains("足球") -> 8.5
            n.contains("篮球") -> 8.0
            n.contains("羽毛球") -> 5.5
            n.contains("乒乓") || n.contains("table tennis") -> 4.0
            n.contains("爬楼") || n.contains("楼梯") -> 8.0
            else -> null
        }
    }
}

internal fun intakeTypeLabel(type: IntakeType): String = when (type) {
    IntakeType.BREAKFAST -> "早餐"
    IntakeType.LUNCH -> "午餐"
    IntakeType.DINNER -> "晚餐"
    IntakeType.SNACK -> "零食"
}

internal fun parseFlexibleDatePublic(text: String?, today: LocalDate): LocalDate? =
    parseFlexibleDate(text, today)
