package com.example.nutritracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1→v2 迁移回归：AppDatabase 仍配置 fallbackToDestructiveMigration，
 * 一旦 MIGRATION_1_2 的 DDL 与实体 schema 不符，真实用户升级会被静默清库。
 * 本测试用 schemas/ 里导出的两版 JSON 复现升级路径，锁住这条底线。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate1To2_preservesV1DataAndCreatesNewTables() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """INSERT INTO meals (name, source, energyKcal100, carbohydrates100, fat100, proteins100)
                   VALUES ('鸡胸肉', 'MANUAL', 133.0, 0.0, 3.6, 24.2)"""
            )
            execSQL(
                """INSERT INTO intakes (mealId, intakeType, amount, unit, dateTime)
                   VALUES (1, 'LUNCH', 150.0, 'g', '2026-09-26T12:00:00')"""
            )
            close()
        }

        // 迁移后 schema 与实体校验不符会在此抛出（而不是线上静默清库）
        helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT name, energyKcal100 FROM meals WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("鸡胸肉", cursor.getString(0))
                assertEquals(133.0, cursor.getDouble(1), 0.001)
            }
            db.query("SELECT amount FROM intakes WHERE mealId = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(150.0, cursor.getDouble(0), 0.001)
            }
            // v2 新表可正常读写
            db.execSQL(
                """INSERT INTO conversations (title, createdAt, updatedAt)
                   VALUES ('迁移后新会话', '2026-09-26T12:00:00', '2026-09-26T12:00:00')"""
            )
            db.query("SELECT COUNT(*) FROM conversations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
