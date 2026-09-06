package com.drone.quiz.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        QuestionEntity::class,
        BankEntity::class,
        PracticeRecordEntity::class,
        QuestionStatsEntity::class,
        ExamRecordEntity::class,
        ExamAnswerEntity::class,
        WrongBookEntity::class,
        StreakLogEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao
    abstract fun bankDao(): BankDao
    abstract fun recordDao(): RecordDao
    abstract fun examDao(): ExamDao
    abstract fun wrongDao(): WrongDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v1 → v2（v2.8.0 多题库 + 新题型）：正式 Migration，老用户学习数据完整保留。
         *
         * 修复历史（v2.8.1）：原实现用 `ALTER TABLE ADD COLUMN` 给非空列附 DEFAULT 值，
         * 而实体未声明 @ColumnInfo(defaultValue=...)，Room 校验期待「无默认值」→ 启动即崩
         * （Migration didn't properly handle）；且 v2 给 ExamRecordEntity 新增的
         * Index("startedAt") 也未建。SQLite 的 ADD COLUMN 对非空列必须带 DEFAULT，
         * 无法产出实体要求的 schema，故改用 Room 标准重建表模式：
         * 建 _new 表（与实体完全一致，无 DEFAULT）→ 拷数据 → 删旧表 → 改名 → 补齐全部索引。
         * 校验失败时 onUpgrade 整体回滚，升级崩过的设备库仍停在 v1，本迁移会重新执行。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- questions：+bankId/+answerText，索引 category/type/bankId 全部重建 ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_questions` (`id` INTEGER NOT NULL, " +
                        "`category` TEXT NOT NULL, `type` TEXT NOT NULL, `question` TEXT NOT NULL, " +
                        "`options` TEXT NOT NULL, `answer` INTEGER NOT NULL, `explanation` TEXT NOT NULL, " +
                        "`bankId` TEXT NOT NULL, `answerText` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `_new_questions` (`id`,`category`,`type`,`question`,`options`,`answer`,`explanation`,`bankId`,`answerText`) " +
                        "SELECT `id`,`category`,`type`,`question`,`options`,`answer`,`explanation`,'drone','' FROM `questions`"
                )
                db.execSQL("DROP TABLE `questions`")
                db.execSQL("ALTER TABLE `_new_questions` RENAME TO `questions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_category` ON `questions` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_type` ON `questions` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_bankId` ON `questions` (`bankId`)")

                // ---- exam_records：+bankId/+extraCounts，并补建 v2 新增的 startedAt 索引 ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_exam_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `total` INTEGER NOT NULL, " +
                        "`singleCount` INTEGER NOT NULL, `judgeCount` INTEGER NOT NULL, `durationSec` INTEGER NOT NULL, " +
                        "`score` REAL, `passed` INTEGER, `bankId` TEXT NOT NULL, `extraCounts` TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `_new_exam_records` (`id`,`startedAt`,`finishedAt`,`total`,`singleCount`,`judgeCount`,`durationSec`,`score`,`passed`,`bankId`,`extraCounts`) " +
                        "SELECT `id`,`startedAt`,`finishedAt`,`total`,`singleCount`,`judgeCount`,`durationSec`,`score`,`passed`,'drone','' FROM `exam_records`"
                )
                db.execSQL("DROP TABLE `exam_records`")
                db.execSQL("ALTER TABLE `_new_exam_records` RENAME TO `exam_records`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_records_startedAt` ON `exam_records` (`startedAt`)")

                // ---- exam_answers：可空 TEXT 列可直接 ADD（无 NOT NULL/DEFAULT，与实体一致） ----
                db.execSQL("ALTER TABLE `exam_answers` ADD COLUMN `detail` TEXT")

                // ---- banks：新表 + 内置题库登记 ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `banks` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `banks` (`id`, `name`, `source`, `createdAt`) " +
                        "VALUES ('drone', '无人机装调题库', 'builtin', ${System.currentTimeMillis()})"
                )
            }
        }

        /**
         * v2 → v3（v2.8.5 题目图片）：questions +images 列。
         * 非空新列禁止 ADD COLUMN ... DEFAULT（实体未声明 defaultValue，Room 校验会崩，
         * v2.8.1 已踩坑）——沿用重建表模式：建 _new 表→拷数据→删旧表→改名→补齐索引。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_questions` (`id` INTEGER NOT NULL, " +
                        "`category` TEXT NOT NULL, `type` TEXT NOT NULL, `question` TEXT NOT NULL, " +
                        "`options` TEXT NOT NULL, `answer` INTEGER NOT NULL, `explanation` TEXT NOT NULL, " +
                        "`bankId` TEXT NOT NULL, `answerText` TEXT NOT NULL, `images` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `_new_questions` (`id`,`category`,`type`,`question`,`options`,`answer`,`explanation`,`bankId`,`answerText`,`images`) " +
                        "SELECT `id`,`category`,`type`,`question`,`options`,`answer`,`explanation`,`bankId`,`answerText`,'' FROM `questions`"
                )
                db.execSQL("DROP TABLE `questions`")
                db.execSQL("ALTER TABLE `_new_questions` RENAME TO `questions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_category` ON `questions` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_type` ON `questions` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_bankId` ON `questions` (`bankId`)")
            }
        }

        /**
         * v3 → v4（v2.8.6 成绩单回显合格线）：exam_records +passLine 列。
         * 走 v2.8.1 验证过的正路：非空新列 ADD COLUMN 带 DEFAULT，且实体声明一致的
         * @ColumnInfo(defaultValue = "60")——两侧对齐 Room 逐列校验才不会崩。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `exam_records` ADD COLUMN `passLine` INTEGER NOT NULL DEFAULT 60")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    // v2 起使用新库文件名，规避旧版本残留数据库的 schema 校验冲突
                    "drone_quiz_v2.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
