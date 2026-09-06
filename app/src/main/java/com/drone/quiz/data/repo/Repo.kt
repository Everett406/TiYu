package com.drone.quiz.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.drone.quiz.data.db.AppDatabase
import com.drone.quiz.data.db.BankEntity
import com.drone.quiz.data.db.CatCount
import com.drone.quiz.data.db.ExamAnswerEntity
import com.drone.quiz.data.db.ExamRecordEntity
import com.drone.quiz.data.db.PracticeRecordEntity
import com.drone.quiz.data.db.QuestionEntity
import com.drone.quiz.data.db.QuestionStatsEntity
import com.drone.quiz.data.db.StreakLogEntity
import com.drone.quiz.data.db.TypeCount
import com.drone.quiz.data.db.WrongBookEntity
import com.drone.quiz.util.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val json = Json { ignoreUnknownKeys = true }

data class Question(
    val id: Long,
    val bankId: String,
    val category: String,
    val type: String,          // single | judge | multi | blank | short
    val text: String,
    val options: List<String>,
    val answer: Int,           // single/judge：下标；multi：位掩码
    val answerText: String,    // blank：各空答案（|| 分空、| 分变体）；short：参考答案
    val explanation: String,
    val images: List<String> = emptyList() // v2.8.5：题目图片文件名（存于 bank_images/<bankId>/）
) {
    val isJudge: Boolean get() = type == QuestionTypes.JUDGE

    /** 判断题选项归一（旧数据无 options JSON，也统一给出正确/错误） */
    val optionsOrJudge: List<String>
        get() = if (isJudge) listOf("正确", "错误") else options

    /** multi 正确项下标集合（answer 为位掩码） */
    val multiAnswerSet: Set<Int>
        get() = (0 until options.size.coerceAtLeast(31)).filter { answer and (1 shl it) != 0 }.toSet()

    /** blank 各空可接受答案（外层=空，内层=该空可接受变体；精确匹配） */
    val blankAnswers: List<List<String>>
        get() = answerText.split("||").map { b -> b.split("|") }

    /** 题干中的空位数（连续 3 个以上下划线记一空） */
    val blankCount: Int
        get() = Regex("_{3,}").findAll(text).count()

    /** 正确答案的可读文本（成绩页 / 解析） */
    fun correctAnswerText(): String = when (type) {
        QuestionTypes.JUDGE -> if (answer == 0) "正确" else "错误"
        QuestionTypes.SINGLE -> {
            val label = optionLabel(answer, false)
            val opt = options.getOrNull(answer).orEmpty()
            "$label·$opt"
        }
        QuestionTypes.MULTI -> multiAnswerSet.sorted().joinToString("、") { optionLabel(it, false) }
        QuestionTypes.BLANK -> blankAnswers.joinToString("；") { vs ->
            if (vs.size == 1) vs[0] else vs.joinToString(" / ")
        }
        QuestionTypes.SHORT -> answerText
        else -> ""
    }
}

class Repo(private val db: AppDatabase, private val appContext: Context) {

    private val qDao = db.questionDao()
    private val bDao = db.bankDao()
    private val rDao = db.recordDao()
    private val eDao = db.examDao()
    private val wDao = db.wrongDao()

    /** 学习数据变化 → 推送刷新四款桌面小组件（内含节流与静默失败，不影响数据流） */
    private fun notifyWidgets() {
        runCatching { WidgetUpdater.updateAllAsync(appContext) }
    }

    // ---------- 题库 ----------

    fun banksFlow(): Flow<List<BankEntity>> = bDao.allFlow()

    fun countFlow(): Flow<Int> = qDao.countFlow()

    suspend fun bankListWithCounts(): List<Pair<BankEntity, Int>> = withContext(Dispatchers.IO) {
        bDao.all().map { it to qDao.countByBank(it.id) }
    }

    /** 题库名称（无分类题的展示回退用，v2.9.2）。 */
    suspend fun bankNameOf(bankId: String): String? =
        withContext(Dispatchers.IO) { bDao.byId(bankId)?.name }

    suspend fun typesInBank(bankId: String): List<String> = withContext(Dispatchers.IO) {
        // 输出按规范顺序（单选→多选→填空→判断→简答），只保留题库中真实存在的题型
        val present = qDao.typeCounts(bankId).map { it.type }.toSet()
        QuestionTypes.canonicalOrder.filter { it in present }
    }

    /** 题库内各题型题目数（模考配置自适应用） */
    suspend fun bankTypeCounts(bankId: String): Map<String, Int> = withContext(Dispatchers.IO) {
        qDao.typeCounts(bankId).associate { it.type to it.cnt }
    }

    /** 题库总题数（刷题配置页学习概览用，v2.8.3 分类筛选移除后不再经分类求和） */
    suspend fun bankCount(bankId: String): Int = withContext(Dispatchers.IO) {
        qDao.countByBank(bankId)
    }

    /**
     * 重命名题库（v2.8.7，用户反馈"导入的题库没办法重命名"）。
     * 仅改显示名：trim + 限长 16 + 空名拒绝（保持原名静默返回，UI 侧已先行守卫）。
     * 内置/导入题库均可改——name 只是 banks 表的展示字段，与播种版本/墓碑机制无关。
     */
    suspend fun renameBank(bankId: String, newName: String) = withContext(Dispatchers.IO) {
        val name = newName.trim().take(16)
        if (name.isNotEmpty()) bDao.rename(bankId, name)
    }

    suspend fun categoriesOf(bankId: String): List<CatCount> = qDao.catCounts(bankId)

    fun countByBankFlow(bankId: String): Flow<Int> = qDao.countByBankFlow(bankId)

    /**
     * 启动播种（v2.8.0 多题库版）：保证内置无人机题库就绪 + 版本升级重置。
     * 返回实际加载生效的题库版本；未发生导入返回 -1（语义与旧版一致）。
     */
    suspend fun ensureBankLoaded(context: Context, storedBankVersion: Int): Int = withContext(Dispatchers.IO) {
        val assetsVersion = runCatching {
            context.assets.open("questions.json").use { input ->
                json.decodeFromString<ImportBank>(input.readBytes().decodeToString()).version
            }
        }.getOrNull() ?: return@withContext -1

        bDao.byId(BANK_DRONE) ?: db.withTransaction {
            bDao.upsert(BankEntity(BANK_DRONE, "无人机装调题库", "builtin", System.currentTimeMillis()))
        }

        val needsImport = qDao.countByBank(BANK_DRONE) == 0 || storedBankVersion != assetsVersion
        if (!needsImport) return@withContext -1

        val bytes = runCatching {
            context.assets.open("questions.json").use { it.readBytes() }
        }.getOrNull() ?: return@withContext -1

        // 先在内存中解析并校验全部题目；任何一题不合法都直接失败，不触碰数据库
        val entities = runCatching { buildBuiltinEntities(bytes) }.getOrNull() ?: return@withContext -1

        // 单事务完成：升级时清理学习数据 + 清空旧题 + 写入新题（原子）
        db.withTransaction {
            if (storedBankVersion != 0 && qDao.countByBank(BANK_DRONE) > 0) {
                // 题库升级：旧学习数据的 qid 与新题库必然失配，整体重置最干净
                rDao.clearRecords(); rDao.clearStats(); rDao.clearStreaks()
                eDao.clearExams(); eDao.clearExamAnswers()
                wDao.clear()
            }
            qDao.deleteByBank(BANK_DRONE)
            entities.chunked(400).forEach { qDao.insertAll(it) }
            bDao.upsert(BankEntity(BANK_DRONE, "无人机装调题库", "builtin", System.currentTimeMillis()))
        }
        assetsVersion
    }

    /** 内置示例题库播种：从未播种（且未被删除）时写入，演示多选/填空/简答新题型。 */
    suspend fun ensureSampleLoaded(context: Context, deletedBanks: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            val tombstoned = BANK_SAMPLE in deletedBanks
            val exists = bDao.byId(BANK_SAMPLE) != null && qDao.countByBank(BANK_SAMPLE) > 0
            if (tombstoned || exists) return@withContext false
            val bytes = runCatching {
                context.assets.open("questions_sample.json").use { it.readBytes() }
            }.getOrNull() ?: return@withContext false
            val entities = runCatching { buildBuiltinEntities(bytes, BANK_SAMPLE, "生活常识示例题库") }
                .getOrNull() ?: return@withContext false
            db.withTransaction {
                qDao.deleteByBank(BANK_SAMPLE)
                entities.chunked(400).forEach { qDao.insertAll(it) }
                bDao.upsert(BankEntity(BANK_SAMPLE, "生活常识示例题库", "builtin", System.currentTimeMillis()))
            }
            true
        }

    private fun buildBuiltinEntities(
        bytes: ByteArray,
        bankId: String = BANK_DRONE,
        defaultCategory: String = "无人机装调"
    ): List<QuestionEntity> {
        val bank = json.decodeFromString<ImportBank>(bytes.decodeToString())
        require(bank.questions.isNotEmpty()) { "题库为空" }
        return bank.questions.map { q ->
            val type = BankImport.normalizeType(q.type) ?: QuestionTypes.SINGLE
            val opts = if (type == QuestionTypes.JUDGE) listOf("正确", "错误") else q.options
            // multi 支持两种写法：answer 位掩码 / answers 下标数组（v2.8.2 修复：示例题库
            // 用 answers 数组，此前只读 answer → 校验全失败 → 播种静默失败，示例题库永远不出现）
            val answerMask = when {
                !q.answers.isNullOrEmpty() ->
                    q.answers.fold(0) { acc, i -> acc or (1 shl i.coerceIn(0, 31)) }
                q.answer != null -> q.answer
                else -> 0
            }
            val parsed = ParsedQuestion(
                id = q.id,
                category = q.category.ifBlank { defaultCategory },
                type = type,
                question = q.question,
                options = opts,
                answer = answerMask,
                answerText = q.answerText ?: "",
                explanation = q.explanation
            )
            validateParsed(parsed)
            QuestionEntity(
                id = q.id ?: stableHash(q.question),
                category = parsed.category,
                type = type,
                question = parsed.question,
                options = json.encodeToString(opts),
                answer = parsed.answer,
                explanation = parsed.explanation,
                bankId = bankId,
                answerText = parsed.answerText
            )
        }.distinctBy { it.id }.let { list ->
            // 保证 id 唯一：冲突时重新生成
            val seen = HashSet<Long>()
            list.map { e ->
                if (seen.add(e.id)) e else e.copy(id = stableHash(e.question + seen.size))
            }
        }
    }

    private fun validateParsed(p: ParsedQuestion) {
        when (p.type) {
            QuestionTypes.SINGLE, QuestionTypes.JUDGE ->
                require(p.options.size >= 2 && p.answer in p.options.indices) { "答案越界：${p.question.take(12)}" }
            QuestionTypes.MULTI ->
                require(p.multiAnswerCount() >= 2) { "多选题至少两个正确项：${p.question.take(12)}" }
            QuestionTypes.BLANK -> require(p.answerText.isNotBlank()) { "填空题缺少答案" }
            QuestionTypes.SHORT -> require(p.answerText.isNotBlank()) { "简答题缺少参考答案" }
        }
    }

    private fun ParsedQuestion.multiAnswerCount(): Int = (0..31).count { answer and (1 shl it) != 0 }

    private fun stableHash(s: String): Long = 1125899906842597L.let { h ->
        s.fold(h) { acc, c -> 31 * acc + c.code }.let { if (it < 0) -it else it }
    }

    /**
     * 导入为独立题库（不替换现有题库）；题 id 从全库最大值之后顺序分配，保证全局唯一。
     * v2.8.5：[images] 非空时（ZIP 导入）把被引用的图片落盘到 bank_images/<bankId>/，
     * 实体记录落盘后的实际文件名。图片先落库后写库：任一落盘失败则整体失败，不产生半截题库。
     */
    suspend fun importParsedBank(
        name: String,
        preview: ImportPreview,
        images: Map<String, ByteArray> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        require(preview.ok.isNotEmpty()) { "没有可导入的题目" }
        val bankId = "imp_${System.currentTimeMillis()}"
        // 落盘被引用的图片（key 小写匹配），得到 引用名 → 实际文件名 映射
        val savedNames = HashMap<String, String>()   // key = 引用名小写
        if (images.isNotEmpty()) {
            val taken = mutableSetOf<String>()
            preview.ok.flatMap { it.images }.distinct().forEach { ref ->
                val key = ref.lowercase()
                val bytes = images[key] ?: return@forEach
                savedNames[key] = QuestionImages.saveBankImage(appContext, bankId, ref, bytes, taken)
            }
        }
        var next = (qDao.maxId() ?: 800L) + 1
        val entities = preview.ok.map { p ->
            QuestionEntity(
                id = next++,
                category = p.category.ifBlank { "未分类" },
                type = p.type,
                question = p.question,
                options = json.encodeToString(p.options),
                answer = p.answer,
                explanation = p.explanation,
                bankId = bankId,
                answerText = p.answerText,
                images = json.encodeToString(p.images.map { savedNames[it.lowercase()] ?: it })
            )
        }
        db.withTransaction {
            entities.chunked(400).forEach { qDao.insertAll(it) }
            bDao.upsert(BankEntity(bankId, name, "imported", System.currentTimeMillis()))
        }
        bankId
    }

    /** 删除题库（题目 + 关联学习数据一并清理）；墓碑/切库由调用方处理。 */
    suspend fun deleteBankData(bankId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            wDao.clearOfBank(bankId)
            rDao.clearRecordsOfBank(bankId)
            rDao.clearStatsOfBank(bankId)
            eDao.clearExamAnswersOfBank(bankId)
            eDao.clearExamsOfBank(bankId)
            qDao.deleteByBank(bankId)
            bDao.delete(bankId)
        }
        // v2.8.5：随题库删除其导入的题目图片（独立于 DB 事务，失败不影响数据一致性）
        runCatching { QuestionImages.deleteBank(appContext, bankId) }
    }

    // ---------- 刷题 ----------

    suspend fun loadPractice(
        bankId: String,
        category: String?,
        type: String?,
        random: Boolean,
        // v2.8.6：800→2000——导入大型题库（如驾考 1000+ 题）后，超出部分永远进不了题单；
        // 内存开销可控（2000 题 ≈ 数 MB），答题卡/翻页均为惰性组合
        limit: Int = 2000,
        // v2.8.4 题型多选：types 非空且 size>1 时优先走 IN 查询（type 参数被忽略）
        types: List<String>? = null
    ): List<Question> = withContext(Dispatchers.IO) {
        // 空列表直接短路：Room 对 IN () 空集合会生成非法 SQL 导致崩溃
        val ids = (if (types != null && types.size > 1) qDao.idsByFilterTypes(bankId, category, types)
                   else qDao.idsByFilter(bankId, category, type))
            .let { if (random) it.shuffled() else it }
            .take(limit)
        if (ids.isEmpty()) return@withContext emptyList()
        // 关键：Room 的 IN (:ids) 返回顺序固定按主键升序，会把 shuffled 顺序完全吃掉
        // → 必须按洗牌后的 ids 保序重排（v2.7.3 根因，勿回退）
        val map = qDao.byIds(ids).associateBy { it.id }
        ids.mapNotNull { map[it]?.toQuestion() }
    }

    /** 错题特训：按当前题库 + 筛选（题型/分类）取错题（v2.8.0 修复"特训不按筛选"） */
    suspend fun loadWrongPractice(bankId: String, type: String?, cat: String?): List<Question> =
        withContext(Dispatchers.IO) {
            val ids = wDao.activeWrongIds(bankId, type, cat)
            if (ids.isEmpty()) return@withContext emptyList()
            qDao.byIds(ids).map { it.toQuestion() }
        }

    /** 按会话快照的 id 顺序取题（恢复上次刷题进度时保证与上次完全一致） */
    suspend fun loadPracticeByIds(ids: List<Long>): List<Question> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val map = qDao.byIds(ids).associateBy { it.id }
        ids.mapNotNull { map[it]?.toQuestion() }
    }

    /** 题目搜索：限定当前题库（题干 / 选项 / 解析 / 参考答案 全文 LIKE）。 */
    suspend fun searchQuestions(bankId: String, query: String): List<Question> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) emptyList() else qDao.search(bankId, q).map { it.toQuestion() }
    }

    private fun QuestionEntity.toQuestion() = Question(
        id = id,
        bankId = bankId,
        category = category,
        type = type,
        text = question,
        options = runCatching { json.decodeFromString<List<String>>(options) }.getOrDefault(emptyList()),
        answer = answer,
        answerText = answerText,
        explanation = explanation,
        images = runCatching { json.decodeFromString<List<String>>(images) }.getOrDefault(emptyList())
    )

    /**
     * 记录一次作答（刷题/错题模式）。
     * 错题本插入时显式 addedAt = System.currentTimeMillis()，从根源避免 NOT NULL 崩溃。
     */
    suspend fun recordAnswer(
        qid: Long,
        isCorrect: Boolean,
        mode: String,
        removeThreshold: Int
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            rDao.insertRecord(PracticeRecordEntity(qid = qid, isCorrect = isCorrect, mode = mode, ts = now))
            val st = rDao.statsFor(qid)
            rDao.upsertStats(
                QuestionStatsEntity(
                    qid = qid,
                    attempts = (st?.attempts ?: 0) + 1,
                    correct = (st?.correct ?: 0) + if (isCorrect) 1 else 0,
                    lastResult = isCorrect,
                    lastTs = now
                )
            )
            val wrong = wDao.forQuestion(qid)
            if (!isCorrect) {
                // 加入或强化错题（addedAt 始终显式赋值）
                if (wrong == null) {
                    wDao.upsert(WrongBookEntity(qid = qid, addedAt = now, wrongCount = 1, correctStreak = 0))
                } else {
                    wDao.upsert(
                        wrong.copy(
                            addedAt = if (wrong.removed) now else wrong.addedAt,
                            wrongCount = wrong.wrongCount + 1,
                            correctStreak = 0,
                            removed = false
                        )
                    )
                }
            } else if (wrong != null && !wrong.removed) {
                val streak = wrong.correctStreak + 1
                if (streak >= removeThreshold.coerceIn(1, 3)) {
                    wDao.upsert(wrong.copy(correctStreak = streak, removed = true))
                } else {
                    wDao.upsert(wrong.copy(correctStreak = streak))
                }
            }
            bumpStreak(isCorrect)
        }
        notifyWidgets()
    }

    private suspend fun bumpStreak(correct: Boolean) {
        bumpStreakBulk(1, if (correct) 1 else 0)
    }

    /** 批量累计当日打卡（模考交卷一次写入，避免逐题查询）。 */
    private suspend fun bumpStreakBulk(answered: Int, correct: Int) {
        if (answered <= 0) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val cur = rDao.streakFor(today)
        rDao.upsertStreak(
            StreakLogEntity(
                date = today,
                answered = (cur?.answered ?: 0) + answered,
                correct = (cur?.correct ?: 0) + correct
            )
        )
    }

    // ---------- 模考 ----------

    /**
     * 组卷（v2.8.0）：按题型分别抽题、按题型顺序拼卷（修复选择/判断混排）。
     * @param counts 题型→题数（UI 依据题库实际拥有的题型自适应生成）
     * @param typeOrder 题型顺序；空 = 默认 单选→多选→填空→判断→简答
     */
    suspend fun startExam(
        bankId: String,
        counts: Map<String, Int>,
        durationSec: Int,
        typeOrder: List<String>,
        passLine: Int = 60 // v2.8.6：开考时设定的合格线，随记录存档供成绩单回显
    ): Pair<Long, List<Question>> = withContext(Dispatchers.IO) {
        val order = typeOrder.filter { it in QuestionTypes.canonicalOrder }
            .ifEmpty { QuestionTypes.canonicalOrder }
        val pickedIds = ArrayList<Long>()
        order.forEach { type ->
            val want = counts[type] ?: 0
            if (want > 0) {
                val ids = qDao.idsByFilter(bankId, null, type)
                if (ids.isNotEmpty()) pickedIds += ids.shuffled().take(want)
            }
        }
        // 题库为空时防御性短路：不建卷、不写 exam_records，返回空列表由 UI 提示
        if (pickedIds.isEmpty()) return@withContext 0L to emptyList()
        val byId = qDao.byIds(pickedIds).associateBy { it.id }
        val typeRank = order.withIndex().associate { (i, t) -> t to i }
        val qs = pickedIds.mapNotNull { byId[it]?.toQuestion() }
            .sortedWith(compareBy({ typeRank[it.type] ?: 99 }, { it.id }))
        val examId = db.withTransaction {
            val id = eDao.insertExam(
                ExamRecordEntity(
                    startedAt = System.currentTimeMillis(),
                    total = qs.size,
                    singleCount = qs.count { it.type == QuestionTypes.SINGLE },
                    judgeCount = qs.count { it.type == QuestionTypes.JUDGE },
                    durationSec = durationSec,
                    bankId = bankId,
                    passLine = passLine.coerceIn(1, 100),
                    extraCounts = json.encodeToString(
                        mapOf(
                            QuestionTypes.MULTI to qs.count { it.type == QuestionTypes.MULTI },
                            QuestionTypes.BLANK to qs.count { it.type == QuestionTypes.BLANK },
                            QuestionTypes.SHORT to qs.count { it.type == QuestionTypes.SHORT }
                        ).filterValues { it > 0 }
                    )
                )
            )
            eDao.insertAnswers(qs.map { ExamAnswerEntity(examId = id, qid = it.id) })
                id
            }
            examId to qs
        }

    suspend fun saveExamAnswer(examId: Long, qid: Long, ua: UserAnswer, correct: Boolean) =
        withContext(Dispatchers.IO) {
            // picked 编码：single/judge 下标；multi 位掩码；blank/short 1=已提交
            val pickedEncoded = when {
                ua.picked != null -> ua.picked!!
                ua.texts.isNotEmpty() || ua.text.isNotBlank() -> 1
                else -> return@withContext
            }
            eDao.updateAnswer(
                examId, qid, pickedEncoded, correct,
                json.encodeToString(ua)
            )
        }

    /** 模考进行中的简答自评（交卷前可反复改判）。 */
    suspend fun gradeShortAnswer(examId: Long, qid: Long, ua: UserAnswer, correct: Boolean) =
        saveExamAnswer(examId, qid, ua, correct)

    /**
     * 交卷：计分（多选全对才对 / 填空精确匹配 / 简答按自评，未自评计错）、写错题本、更新打卡。
     */
    suspend fun submitExam(
        examId: Long,
        questions: List<Question>,
        answers: Map<Long, UserAnswer>,
        durationSec: Int,
        passScore: Int,
        removeThreshold: Int
    ): ExamOutcome = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val judged = questions.associate { it.id to (judgeAnswer(it, answers[it.id]) == true) }
        val correctIds = questions.filter { judged[it.id] == true }.map { it.id }
        val score = if (questions.isEmpty()) 0f else correctIds.size * 100f / questions.size
        val passed = score >= passScore
        db.withTransaction {
            eDao.finishExam(examId, now, score, passed)
            questions.forEach { q ->
                val ua = answers[q.id]
                val ok = judged[q.id] == true
                if (ua != null) {
                    saveExamAnswer(examId, q.id, ua, ok)
                }
                rDao.insertRecord(
                    PracticeRecordEntity(qid = q.id, isCorrect = ok, mode = "exam", ts = now)
                )
                val st = rDao.statsFor(q.id)
                rDao.upsertStats(
                    QuestionStatsEntity(
                        qid = q.id,
                        attempts = (st?.attempts ?: 0) + 1,
                        correct = (st?.correct ?: 0) + if (ok) 1 else 0,
                        lastResult = ok,
                        lastTs = now
                    )
                )
                val wrong = wDao.forQuestion(q.id)
                if (!ok) {
                    if (wrong == null) {
                        wDao.upsert(WrongBookEntity(qid = q.id, addedAt = now, wrongCount = 1, correctStreak = 0))
                    } else {
                        wDao.upsert(
                            wrong.copy(
                                addedAt = if (wrong.removed) now else wrong.addedAt,
                                wrongCount = wrong.wrongCount + 1,
                                correctStreak = 0,
                                removed = false
                            )
                        )
                    }
                } else if (wrong != null && !wrong.removed) {
                    val streak = wrong.correctStreak + 1
                    if (streak >= removeThreshold.coerceIn(1, 3)) {
                        wDao.upsert(wrong.copy(correctStreak = streak, removed = true))
                    } else {
                        wDao.upsert(wrong.copy(correctStreak = streak))
                    }
                }
            }
            bumpStreakBulk(questions.size, correctIds.size)
        }
        notifyWidgets()
        ExamOutcome(
            score = score,
            passed = passed,
            correct = correctIds.size,
            total = questions.size,
            passLine = passScore,
            wrongQuestions = questions.filter { judged[it.id] != true },
            userAnswers = questions.associate { it.id to displayUserAnswer(it, answers[it.id]) }
        )
    }

    data class ExamOutcome(
        val score: Float,
        val passed: Boolean,
        val correct: Int,
        val total: Int,
        val passLine: Int = 60, // v2.8.6：本场设定的合格线（成绩单回显）
        val wrongQuestions: List<Question>,
        val userAnswers: Map<Long, String> = emptyMap()
    )

    fun recentExams(bankId: String): Flow<List<ExamRecordEntity>> = eDao.recentExams(bankId, 20)

    /**
     * 从数据库重建历史模考成绩（供"最近模考"记录点击进入成绩页使用）。
     * 未交卷/不存在 → null。
     */
    suspend fun loadExamOutcome(examId: Long): ExamOutcome? = withContext(Dispatchers.IO) {
        if (examId <= 0) return@withContext null
        val rec = eDao.examById(examId) ?: return@withContext null
        val score = rec.score ?: return@withContext null
        val answers = eDao.answersFor(examId)
        val map = qDao.byIds(answers.map { it.qid }).associateBy { it.id }
        val qs = answers.mapNotNull { map[it.qid]?.toQuestion() }
        val uaById = answers.associate { answer ->
            val ua = answer.detail?.let { raw ->
                runCatching { json.decodeFromString<UserAnswer>(raw) }.getOrNull()
            }
            answer.qid to ua
        }
        ExamOutcome(
            score = score,
            passed = rec.passed ?: false,
            correct = answers.count { it.isCorrect == true },
            total = rec.total,
            passLine = rec.passLine,
            wrongQuestions = answers
                .filter { it.isCorrect == false }
                .mapNotNull { map[it.qid]?.toQuestion() },
            userAnswers = qs.associate { it.id to displayUserAnswer(it, uaById[it.id]) }
        )
    }

    /** 模考原始记录（成绩卡取用时/开考日期用，v2.10.0） */
    suspend fun examRecord(examId: Long): ExamRecordEntity? = withContext(Dispatchers.IO) {
        eDao.examById(examId)
    }

    /** 放弃考试：删除该次模考的记录与作答（不再残留"进行中"幽灵记录）；也用于删除已完成记录 */
    suspend fun abandonExam(examId: Long) = withContext(Dispatchers.IO) {
        if (examId <= 0) return@withContext
        db.withTransaction {
            eDao.deleteAnswersFor(examId)
            eDao.deleteExam(examId)
        }
        notifyWidgets()
    }

    /**
     * 恢复未完成的模考（进程重启后 SessionHolder 已空时从 DB 重建）。
     * 返回 null 表示无法恢复（不存在/已交卷/题目已被题库升级清除）。
     */
    suspend fun resumeExam(
        examId: Long
    ): Triple<ExamRecordEntity, List<Question>, Map<Long, UserAnswer>>? =
        withContext(Dispatchers.IO) {
            if (examId <= 0) return@withContext null
            val exam = eDao.examById(examId) ?: return@withContext null
            if (exam.score != null) return@withContext null // 已完成，无可恢复
            val answers = eDao.answersFor(examId)
            val qs = qDao.byIds(answers.map { it.qid }).map { it.toQuestion() }
            if (qs.isEmpty()) return@withContext null
            val qidSet = qs.map { it.id }.toSet()
            Triple(
                exam,
                qs,
                answers.filter { (it.picked != null || it.detail != null) && it.qid in qidSet }
                    .associate { answer ->
                        val ua = answer.detail?.let { raw ->
                            runCatching { json.decodeFromString<UserAnswer>(raw) }.getOrNull()
                        } ?: UserAnswer(picked = answer.picked)
                        answer.qid to ua
                    }
            )
        }

    // ---------- 错题本 ----------

    fun activeWrong(bankId: String): Flow<List<com.drone.quiz.data.db.WrongWithQuestion>> =
        wDao.activeWrongWithQuestions(bankId)

    suspend fun removeWrongForever(qid: Long) = withContext(Dispatchers.IO) { wDao.markRemoved(qid) }

    // ---------- 首页统计（按题库隔离；打卡/今日为全局习惯数据） ----------

    fun totalAnsweredFlow(): Flow<Int> = rDao.totalAnsweredFlow()

    fun answeredDistinctFlow(bankId: String): Flow<Int> = rDao.answeredDistinctByBankFlow(bankId)

    suspend fun wrongCount(bankId: String): Int = withContext(Dispatchers.IO) {
        wDao.activeWrongIds(bankId, null, null).size
    }

    suspend fun last7Days(): List<DayStat> = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val map = rDao.recentStreaks(30).associateBy { it.date }
        (6 downTo 0).map { off ->
            val d = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -off)
            }.time
            val key = fmt.format(d)
            val s = map[key]
            DayStat(
                label = listOf("日", "一", "二", "三", "四", "五", "六")[
                    java.util.Calendar.getInstance().apply { time = d }.get(java.util.Calendar.DAY_OF_WEEK) - 1
                ],
                isToday = off == 0,
                answered = s?.answered ?: 0,
                correct = s?.correct ?: 0
            )
        }
    }

    /**
     * 按题库隔离的今日/近 7 天统计（v2.8.2）：practice_records JOIN questions 过滤。
     * 返回 (近 7 天逐日, 今日已刷, 今日答对)；打卡连击（streak）仍为全局习惯数据。
     */
    suspend fun last7DaysByBank(bankId: String): Triple<List<DayStat>, Int, Int> =
        withContext(Dispatchers.IO) {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val todayStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val since = todayStart - 6 * 86_400_000L
            val rows = rDao.rowsByBankSince(bankId, since)
            // 逐日聚合（本地时区 yyyy-MM-dd）
            val byDay = rows.groupBy { fmt.format(java.util.Date(it.ts)) }
                .mapValues { (_, list) ->
                    list.count() to list.count { it.isCorrect }
                }
            val dayStats = (6 downTo 0).map { off ->
                val d = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, -off)
                }.time
                val key = fmt.format(d)
                val (a, c) = byDay[key] ?: (0 to 0)
                DayStat(
                    label = listOf("日", "一", "二", "三", "四", "五", "六")[
                        java.util.Calendar.getInstance().apply { time = d }.get(java.util.Calendar.DAY_OF_WEEK) - 1
                    ],
                    isToday = off == 0,
                    answered = a,
                    correct = c
                )
            }
            val today = rows.filter { it.ts >= todayStart }
            Triple(dayStats, today.size, today.count { it.isCorrect })
        }

    suspend fun accuracy(bankId: String): Float = withContext(Dispatchers.IO) {
        val a = rDao.totalAttemptsByBank(bankId)
        val c = rDao.totalCorrectByBank(bankId)
        if (a == 0) 0f else c.toFloat() / a
    }

    data class DayStat(val label: String, val isToday: Boolean, val answered: Int, val correct: Int)

    suspend fun streakDays(): Int = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val dates = rDao.recentStreaks(90).map { it.date }.toSet()
        var count = 0
        var off = 0
        while (true) {
            val d = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -off)
            }.time
            if (fmt.format(d) in dates) {
                count++
                off++
            } else break
        }
        count
    }

    /** 清空全部学习数据（内置题库由调用方随后重新播种）。 */
    suspend fun clearAllRecords() = withContext(Dispatchers.IO) {
        db.withTransaction {
            rDao.clearRecords(); rDao.clearStats(); rDao.clearStreaks(); wDao.clear()
            // 最近模考也属于做题记录：一并清空（含未完成的"进行中"残留）
            eDao.clearExamAnswers(); eDao.clearExams()
            // 清空记录 = 出厂：题库与墓碑一并重置，内置题库将重新播种
            qDao.clear()
            bDao.all().forEach { bDao.delete(it.id) }
        }
    }

    /**
     * 最近 days 天里，每天首次刷题的时刻（小时浮点，19.5 = 19:30）。
     * 用于智能提醒：学习用户习惯的开始刷题时间，替代固定 20:00。
     */
    suspend fun habitStartHours(days: Int = 10): List<Float> = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val since = System.currentTimeMillis() - days * 86_400_000L
        rDao.practiceTsSince(since)
            .groupBy { fmt.format(java.util.Date(it)) }
            .map { (_, list) -> list.min() }   // 每天最早一次作答
            .map { ts ->
                val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
                c.get(java.util.Calendar.HOUR_OF_DAY) + c.get(java.util.Calendar.MINUTE) / 60f
            }
    }

    /** 今天是否已刷过题（已刷则智能提醒当天不再打扰）。 */
    suspend fun todayAnsweredCount(): Int = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        rDao.streakFor(fmt.format(java.util.Date()))?.answered ?: 0
    }

    companion object {
        const val BANK_DRONE = "drone"
        const val BANK_SAMPLE = "sample"
    }
}
