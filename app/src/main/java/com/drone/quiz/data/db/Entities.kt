package com.drone.quiz.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "questions",
    indices = [Index("category"), Index("type"), Index("bankId")]
)
data class QuestionEntity(
    @PrimaryKey val id: Long,
    val category: String,
    val type: String, // "single" | "judge" | "multi" | "blank" | "short"（v2.8.0 扩展）
    val question: String,
    val options: String, // JSON array string（判断题固定 ["正确","错误"]；填空/简答为空数组）
    val answer: Int,     // single/judge：正确选项下标；multi：正确项位掩码（bit i = 选项 i）
    val explanation: String,
    val bankId: String = "drone",     // 所属题库（v2.8.0 多题库）
    val answerText: String = "",      // blank：各空答案（|| 分隔空，| 分隔同空可接受变体）；short：参考答案
    val images: String = ""           // v2.8.5：题目图片文件名 JSON 数组（文件存于 bank_images/<bankId>/）
)

/** 题库登记表（v2.8.0）：内置题库（assets 播种）与导入题库统一管理。 */
@Entity(tableName = "banks")
data class BankEntity(
    @PrimaryKey val id: String,       // "drone" | "sample" | "imp_<时间戳>"
    val name: String,
    val source: String,               // "builtin" | "imported"
    val createdAt: Long
)

@Entity(tableName = "practice_records", indices = [Index("qid"), Index("ts")])
data class PracticeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val qid: Long,
    val isCorrect: Boolean,
    val mode: String, // practice | exam | wrong
    val ts: Long
)

@Entity(tableName = "question_stats")
data class QuestionStatsEntity(
    @PrimaryKey val qid: Long,
    val attempts: Int = 0,
    val correct: Int = 0,
    val lastResult: Boolean? = null,
    val lastTs: Long = 0
)

@Entity(tableName = "exam_records", indices = [Index("startedAt")])
data class ExamRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val total: Int,
    val singleCount: Int,
    val judgeCount: Int,
    val durationSec: Int,
    val score: Float? = null,
    val passed: Boolean? = null,
    val bankId: String = "drone",        // v2.8.0：所属题库（按题库隔离展示）
    val extraCounts: String = "",        // v2.8.0：新题型计数 JSON，如 {"multi":5,"blank":3,"short":2}
    // v2.8.6：开考时设定的合格线（成绩单回显用）。非空新列必须带 DEFAULT 且实体声明一致的
    // @ColumnInfo(defaultValue)——v2.8.1 已踩坑（Room 逐列校验 schema）
    @ColumnInfo(defaultValue = "60") val passLine: Int = 60
)

@Entity(tableName = "exam_answers", indices = [Index("examId"), Index("qid")])
data class ExamAnswerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examId: Long,
    val qid: Long,
    val picked: Int? = null,  // null = 未答；single/judge 选项下标；multi 位掩码；blank/short 1=已提交
    val isCorrect: Boolean? = null,
    val detail: String? = null // v2.8.0：UserAnswer JSON（填空各空文本 / 简答内容）
)

/**
 * 错题本。addedAt 为非空列，插入时必须显式赋值（修复 NOT NULL constraint 崩溃）。
 */
@Entity(tableName = "wrongbook", indices = [Index(value = ["qid"], unique = true)])
data class WrongBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val qid: Long,
    val addedAt: Long = 0,
    val wrongCount: Int = 1,
    val correctStreak: Int = 0,
    val removed: Boolean = false
)

@Entity(tableName = "streak_log")
data class StreakLogEntity(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val answered: Int = 0,
    val correct: Int = 0
)
