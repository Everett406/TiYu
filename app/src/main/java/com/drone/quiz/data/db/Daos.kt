package com.drone.quiz.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class CatCount(val category: String, val cnt: Int)
data class TypeCount(val type: String, val cnt: Int)

/** 作答记录行（按题库 JOIN 过滤后取回，用于今日/近 7 天逐日聚合） */
data class TsCorrect(val ts: Long, val isCorrect: Boolean)

@Dao
interface BankDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bank: BankEntity)

    @Query("SELECT * FROM banks ORDER BY createdAt ASC")
    fun allFlow(): Flow<List<BankEntity>>

    @Query("SELECT * FROM banks ORDER BY createdAt ASC")
    suspend fun all(): List<BankEntity>

    @Query("SELECT * FROM banks WHERE id = :id")
    suspend fun byId(id: String): BankEntity?

    @Query("DELETE FROM banks WHERE id = :id")
    suspend fun delete(id: String)

    /** 重命名（v2.8.7）：仅改显示名，id/source/createdAt 不动；校验在 Repo 层。 */
    @Query("UPDATE banks SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)
}

@Dao
interface QuestionDao {
    @Query("SELECT COUNT(*) FROM questions")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId")
    fun countByBankFlow(bankId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId")
    suspend fun countByBank(bankId: String): Int

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun count(): Int

    @Query("SELECT MAX(id) FROM questions")
    suspend fun maxId(): Long?

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun byId(id: Long): QuestionEntity?

    @Query("SELECT * FROM questions WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<QuestionEntity>

    @Query(
        "SELECT id FROM questions WHERE bankId = :bankId " +
            "AND (:cat IS NULL OR category = :cat) " +
            "AND (:type IS NULL OR type = :type)"
    )
    suspend fun idsByFilter(bankId: String, cat: String?, type: String?): List<Long>

    // v2.8.4 题型多选：刷题配置页可同时勾选多种题型，IN 查询一次取齐
    @Query(
        "SELECT id FROM questions WHERE bankId = :bankId " +
            "AND (:cat IS NULL OR category = :cat) " +
            "AND type IN (:types)"
    )
    suspend fun idsByFilterTypes(bankId: String, cat: String?, types: List<String>): List<Long>

    @Query("SELECT DISTINCT category FROM questions WHERE bankId = :bankId ORDER BY category")
    suspend fun categories(bankId: String): List<String>

    // v2.19.32 模考「优先未答」：LEFT JOIN question_stats，取 attempts=0 的题。
    // 「未答过」的口径 = question_stats 里没有记录，或记录了但 attempts 仍为 0
    // （后者是历史脏数据的可能形态，宁可算作未答，也不要漏掉本可抽的新题）。
    // COALESCE(attempts,0)=0 而不是 IS NULL：见上方注释。
    @Query(
        "SELECT q.id FROM questions q " +
            "LEFT JOIN question_stats s ON s.qid = q.id " +
            "WHERE q.bankId = :bankId AND q.type = :type " +
            "AND COALESCE(s.attempts, 0) = 0"
    )
    suspend fun unseenIdsByType(bankId: String, type: String): List<Long>

    /** 同一口径的题量统计（模考配置页展示「未答 N 题」，避免用户开了开关却看不到效果依据） */
    @Query(
        "SELECT COUNT(*) FROM questions q " +
            "LEFT JOIN question_stats s ON s.qid = q.id " +
            "WHERE q.bankId = :bankId AND COALESCE(s.attempts, 0) = 0"
    )
    fun unseenCountByBankFlow(bankId: String): Flow<Int>

    @Query(
        "SELECT * FROM questions WHERE bankId = :bankId " +
            "AND (question LIKE '%' || :q || '%' OR options LIKE '%' || :q || '%' " +
            "OR explanation LIKE '%' || :q || '%' OR answerText LIKE '%' || :q || '%') " +
            "ORDER BY id LIMIT 80"
    )
    suspend fun search(bankId: String, q: String): List<QuestionEntity>

    @Query("SELECT category AS category, COUNT(*) AS cnt FROM questions WHERE bankId = :bankId GROUP BY category ORDER BY category")
    suspend fun catCounts(bankId: String): List<CatCount>

    @Query("SELECT type AS type, COUNT(*) AS cnt FROM questions WHERE bankId = :bankId GROUP BY type")
    suspend fun typeCounts(bankId: String): List<TypeCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<QuestionEntity>)

    @Query("DELETE FROM questions")
    suspend fun clear()

    @Query("DELETE FROM questions WHERE bankId = :bankId")
    suspend fun deleteByBank(bankId: String)
}

@Dao
interface RecordDao {
    @Insert
    suspend fun insertRecord(r: PracticeRecordEntity)

    @Query("SELECT ts FROM practice_records WHERE ts >= :since ORDER BY ts ASC")
    suspend fun practiceTsSince(since: Long): List<Long>

    @Query("SELECT * FROM question_stats WHERE qid = :qid")
    suspend fun statsFor(qid: Long): QuestionStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStats(s: QuestionStatsEntity)

    @Upsert
    suspend fun upsertStreak(s: StreakLogEntity)

    @Query("SELECT * FROM streak_log WHERE date = :date")
    suspend fun streakFor(date: String): StreakLogEntity?

    @Query("SELECT * FROM streak_log ORDER BY date DESC LIMIT :n")
    suspend fun recentStreaks(n: Int): List<StreakLogEntity>

    @Query("SELECT COALESCE(SUM(answered),0) FROM streak_log")
    fun totalAnsweredFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM question_stats WHERE attempts > 0")
    fun answeredDistinctFlow(): Flow<Int>

    // ---- 按题库隔离的统计（v2.8.0：经 questions 表 JOIN 过滤） ----

    @Query(
        "SELECT COUNT(DISTINCT s.qid) FROM question_stats s " +
            "JOIN questions q ON s.qid = q.id WHERE q.bankId = :bankId AND s.attempts > 0"
    )
    fun answeredDistinctByBankFlow(bankId: String): Flow<Int>

    /** 一次性取值版（v2.19.48 预估通过率用，避免为一屏数字开一条常驻 Flow） */
    @Query(
        "SELECT COUNT(DISTINCT s.qid) FROM question_stats s " +
            "JOIN questions q ON s.qid = q.id WHERE q.bankId = :bankId AND s.attempts > 0"
    )
    suspend fun answeredDistinctByBank(bankId: String): Int

    @Query(
        "SELECT COALESCE(SUM(s.correct),0) FROM question_stats s " +
            "JOIN questions q ON s.qid = q.id WHERE q.bankId = :bankId AND s.attempts > 0"
    )
    suspend fun totalCorrectByBank(bankId: String): Int

    @Query(
        "SELECT COALESCE(SUM(s.attempts),0) FROM question_stats s " +
            "JOIN questions q ON s.qid = q.id WHERE q.bankId = :bankId AND s.attempts > 0"
    )
    suspend fun totalAttemptsByBank(bankId: String): Int

    /**
     * 按题型的刷题统计（v2.19.48 预估通过率）。
     *
     * 返回的 distinctQuestions 是**去重题数**——刷 200 次同一批 40 道题，
     * 证据量应当只算 40，而不是 200。
     */
    @Query(
        "SELECT q.type AS type, COUNT(DISTINCT s.qid) AS distinctQuestions, " +
        "       COALESCE(SUM(s.correct),0) AS correctSum, " +
        "       COALESCE(SUM(s.attempts),0) AS attempts " +
        "FROM question_stats s JOIN questions q ON s.qid = q.id " +
        "WHERE q.bankId = :bankId AND s.attempts > 0 GROUP BY q.type"
    )
    suspend fun practiceTypeBreakdown(bankId: String): List<PracticeTypeRow>

    /** 按题库过滤的作答记录行（v2.8.2：今日/近 7 天逐日聚合用；ts 有索引） */
    @Query(
        "SELECT r.ts AS ts, r.isCorrect AS isCorrect FROM practice_records r " +
            "JOIN questions q ON r.qid = q.id WHERE q.bankId = :bankId AND r.ts >= :since"
    )
    suspend fun rowsByBankSince(bankId: String, since: Long): List<TsCorrect>

    @Query("SELECT COALESCE(SUM(correct),0) FROM question_stats WHERE attempts > 0")
    suspend fun totalCorrect(): Int

    @Query("SELECT COALESCE(SUM(attempts),0) FROM question_stats WHERE attempts > 0")
    suspend fun totalAttempts(): Int

    @Query("SELECT COUNT(DISTINCT qid) FROM wrongbook WHERE removed = 0")
    fun wrongCountFlow(): Flow<Int>

    @Query("DELETE FROM practice_records")
    suspend fun clearRecords()

    @Query("DELETE FROM question_stats")
    suspend fun clearStats()

    /** 删除题库时清理其孤儿学习数据（题目行删除后 JOIN 不可见，但行仍在） */
    @Query("DELETE FROM question_stats WHERE qid IN (SELECT id FROM questions WHERE bankId = :bankId)")
    suspend fun clearStatsOfBank(bankId: String)

    @Query("DELETE FROM practice_records WHERE qid IN (SELECT id FROM questions WHERE bankId = :bankId)")
    suspend fun clearRecordsOfBank(bankId: String)

    @Query("DELETE FROM streak_log")
    suspend fun clearStreaks()
}

/** 模考中某题型（某一场内）的作答对错统计（v2.19.48 预估通过率用） */
data class ExamTypeRow(
    val examId: Long,
    val startedAt: Long,
    val type: String,
    val answered: Int,
    val correct: Int
)

/** 刷题中某题型的去重题数与正确率（v2.19.48） */
data class PracticeTypeRow(
    val type: String,
    val distinctQuestions: Int,
    val correctSum: Int,
    val attempts: Int
)

@Dao
interface ExamDao {

    /**
     * 按场次 + 题型汇总模考作答（v2.19.48）。
     *
     * 只取已交卷（score 非空）的记录。题型靠 join questions 拿到——
     * exam_records 里的 singleCount/judgeCount/extraCounts 只在开考时写、
     * 反映的是**计划**构成，这里要的是**实际作答**构成。
     */
    @Query(
        "SELECT er.id AS examId, er.startedAt AS startedAt, q.type AS type, " +
        "       COUNT(ea.id) AS answered, " +
        "       SUM(CASE WHEN ea.isCorrect = 1 THEN 1 ELSE 0 END) AS correct " +
        "FROM exam_records er " +
        "JOIN exam_answers ea ON ea.examId = er.id " +
        "JOIN questions q ON q.id = ea.qid " +
        "WHERE er.bankId = :bankId AND er.score IS NOT NULL " +
        "GROUP BY er.id, q.type"
    )
    suspend fun typeBreakdown(bankId: String): List<ExamTypeRow>

    /** 最近的已交卷模考（v2.19.48 时间衰减用） */
    @Query(
        "SELECT * FROM exam_records WHERE bankId = :bankId AND score IS NOT NULL " +
        "ORDER BY startedAt DESC LIMIT :n"
    )
    suspend fun recentScoredExams(bankId: String, n: Int): List<ExamRecordEntity>

    @Insert
    suspend fun insertExam(e: ExamRecordEntity): Long

    @Insert
    suspend fun insertAnswers(list: List<ExamAnswerEntity>)

    @Query("UPDATE exam_records SET finishedAt = :finishedAt, score = :score, passed = :passed WHERE id = :examId")
    suspend fun finishExam(examId: Long, finishedAt: Long, score: Float, passed: Boolean)

    @Query("SELECT * FROM exam_records WHERE id = :examId")
    suspend fun examById(examId: Long): ExamRecordEntity?

    @Query("SELECT * FROM exam_records WHERE bankId = :bankId ORDER BY startedAt DESC LIMIT :n")
    fun recentExams(bankId: String, n: Int): Flow<List<ExamRecordEntity>>

    /** 已交卷（有分数）的模考，倒序（v2.19.50 首页模考走势） */
    @Query(
        "SELECT * FROM exam_records WHERE bankId = :bankId AND score IS NOT NULL " +
            "ORDER BY startedAt DESC LIMIT :n"
    )
    fun recentScoredExamsFlow(bankId: String, n: Int): Flow<List<ExamRecordEntity>>

    @Query("SELECT * FROM exam_answers WHERE examId = :examId")
    suspend fun answersFor(examId: Long): List<ExamAnswerEntity>

    @Query("UPDATE exam_answers SET picked = :picked, isCorrect = :correct, detail = :detail WHERE examId = :examId AND qid = :qid")
    suspend fun updateAnswer(examId: Long, qid: Long, picked: Int, correct: Boolean, detail: String?)

    @Query("SELECT * FROM exam_answers WHERE examId = :examId AND picked IS NULL")
    suspend fun unanswered(examId: Long): List<ExamAnswerEntity>

    @Query("DELETE FROM exam_records")
    suspend fun clearExams()

    @Query("DELETE FROM exam_answers")
    suspend fun clearExamAnswers()

    @Query("DELETE FROM exam_records WHERE id = :examId")
    suspend fun deleteExam(examId: Long)

    @Query("DELETE FROM exam_answers WHERE examId = :examId")
    suspend fun deleteAnswersFor(examId: Long)

    @Query("DELETE FROM exam_records WHERE bankId = :bankId")
    suspend fun clearExamsOfBank(bankId: String)

    @Query("DELETE FROM exam_answers WHERE examId IN (SELECT id FROM exam_records WHERE bankId = :bankId)")
    suspend fun clearExamAnswersOfBank(bankId: String)
}

@Dao
interface WrongDao {
    @Query("SELECT * FROM wrongbook WHERE qid = :qid")
    suspend fun forQuestion(qid: Long): WrongBookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: WrongBookEntity)

    @Query(
        "SELECT w.*, q.question AS question, q.category AS category, q.type AS type, " +
            "q.options AS options, q.answer AS answer, q.explanation AS explanation, " +
            "q.answerText AS answerText " +
            "FROM wrongbook w JOIN questions q ON w.qid = q.id " +
            "WHERE w.removed = 0 AND q.bankId = :bankId ORDER BY w.addedAt DESC"
    )
    fun activeWrongWithQuestions(bankId: String): Flow<List<WrongWithQuestion>>

    @Query(
        "SELECT w.qid FROM wrongbook w JOIN questions q ON w.qid = q.id " +
            "WHERE w.removed = 0 AND q.bankId = :bankId " +
            "AND (:type IS NULL OR q.type = :type) AND (:cat IS NULL OR q.category = :cat)"
    )
    suspend fun activeWrongIds(bankId: String, type: String?, cat: String?): List<Long>

    @Query("SELECT COUNT(*) FROM wrongbook WHERE removed = 0")
    fun wrongCountFlow(): Flow<Int>

    /** 按题库隔离的错题数（v2.19.35：错题优先开关的说明文案用） */
    @Query(
        "SELECT COUNT(*) FROM wrongbook w JOIN questions q ON w.qid = q.id " +
            "WHERE w.removed = 0 AND q.bankId = :bankId"
    )
    fun activeWrongCountByBankFlow(bankId: String): Flow<Int>

    @Query("UPDATE wrongbook SET removed = 1 WHERE qid = :qid")
    suspend fun markRemoved(qid: Long)

    @Query("DELETE FROM wrongbook")
    suspend fun clear()

    @Query("DELETE FROM wrongbook WHERE qid IN (SELECT id FROM questions WHERE bankId = :bankId)")
    suspend fun clearOfBank(bankId: String)

    @Query("UPDATE wrongbook SET removed = 0, correctStreak = 0 WHERE qid = :qid")
    suspend fun reAdd(qid: Long)
}

data class WrongWithQuestion(
    val id: Long,
    val qid: Long,
    val addedAt: Long,
    val wrongCount: Int,
    val correctStreak: Int,
    val removed: Boolean,
    val question: String,
    val category: String,
    val type: String,
    val options: String,
    val answer: Int,
    val explanation: String,
    val answerText: String = ""
)
