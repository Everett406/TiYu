package com.drone.quiz.ui.ai

import com.drone.quiz.data.repo.Question
import com.drone.quiz.data.repo.optionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 一条对话消息。role 取 system / user / assistant。 */
data class AiMessage(val role: String, val content: String)

/**
 * AI 助手客户端（OpenAI 兼容的 `/chat/completions`，DeepSeek 原生即此格式）。
 *
 * **为什么默认 DeepSeek**：它提供 OpenAI 完全兼容的接口，换成 Kimi / 通义 / 本地
 * Ollama / LM Studio 只要改 baseUrl + model 即可，代码一行不动。故全走 OpenAI 格式，
 * 不写任何 DeepSeek 私有字段。
 *
 * **流式**：`stream = true`，逐行读 SSE（`data: {...}`），把
 * `choices[0].delta.content` 增量回调出去；`data: [DONE]` 收尾。
 */
class AiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Double,
    private val maxTokens: Int
) {
    private val client = OkHttpClient.Builder()
        // 读超时放宽：流式下两包之间可能停很久（模型在"想"），不能按整体时长卡。
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 只取 text 的一次性非流式调用，用于「连接测试」。 */
    suspend fun test(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildRequest(
                listOf(
                    AiMessage("user", "只回复两个字：正常")
                ),
                stream = false
            )
            client.newCall(requestOf(body)).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw AiError(httpMessage(resp.code, text))
                val content = json.parseToJsonElement(text).jsonObject
                    .getContentText()
                    ?: "（返回里没找到正文）"
                content
            }
        }
    }

    /**
     * 流式对话。`onDelta` 每次收到一小段文本即回调（可能在主线程被调用）。
     * 返回完整文本。取消协程会主动断掉 HTTP 连接。
     */
    suspend fun stream(
        messages: List<AiMessage>,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val req = requestOf(buildRequest(messages, stream = true))
        val call = client.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw AiError(httpMessage(resp.code, resp.body?.string().orEmpty()))
                val body = resp.body ?: throw AiError("返回体为空")
                val source = body.source()
                val sb = StringBuilder()
                while (!source.exhausted()) {
                    // 协程被取消（用户关闭弹窗 / 点了停止）时立刻断开，别让请求挂着
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val delta = runCatching {
                        json.parseToJsonElement(payload).jsonObject
                            .getContentText()
                    }.getOrNull() ?: continue
                    if (delta.isEmpty()) continue
                    sb.append(delta)
                    onDelta(delta)
                }
                if (sb.isEmpty()) throw AiError("模型没有返回任何内容")
                sb.toString()
            }
        } catch (e: IOException) {
            throw AiError("网络不通或已中断：${e.message ?: "连接失败"}")
        } finally {
            runCatching { call.cancel() }
        }
    }

    // ---------- 内部 ----------

    private fun requestOf(body: String): Request = Request.Builder()
        .url("${baseUrl.trimEnd('/')}/chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .post(body.toRequestBody(JSON))
        .build()

    private fun buildRequest(messages: List<AiMessage>, stream: Boolean): String =
        buildJsonObject {
            put("model", model)
            put("stream", stream)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("messages", kotlinx.serialization.json.buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
        }.toString()

    /** 取 choices[0].delta.content（非流式则是 message.content）。 */
    private fun JsonObject.getContentText(): String? {
        val arr = this["choices"]?.jsonArray ?: return null
        val first = arr.firstOrNull()?.jsonObject ?: return null
        first["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNullSafe()
            ?.let { return it }
        return first["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNullSafe()
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else this.content

    private fun httpMessage(code: Int, body: String): String {
        val detail = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            val e = o["error"]
            when (e) {
                is JsonObject -> e["message"]?.jsonPrimitive?.content
                is JsonPrimitive -> e.content
                else -> null
            }
        }.getOrNull()
        val base = when (code) {
            401 -> "API Key 无效或已过期"
            402 -> "账户余额不足"
            403 -> "无访问权限（Key 或该模型不可用）"
            404 -> "接口地址或模型名不对"
            429 -> "请求太频繁或额度已用尽"
            in 500..599 -> "服务端错误"
            else -> "HTTP $code"
        }
        return if (detail.isNullOrBlank()) base else "$base：$detail"
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** 面向用户的中文错误。 */
class AiError(message: String) : Exception(message)

/** 速查预设：把当前题变成一段可读的上下文。 */
object QuestionContext {

    /**
     * 把刷题页当前这一题编成 system 消息。
     *
     * **为什么要带正确答案**：用户问的多半是"为什么选 B"，模型必须知道标准答案才能
     * 讲清楚；否则它只能猜，答出来的解释经常对不上。
     * **为什么标注我的作答**：模型才能针对性地说"你选的那个为什么不对"。
     *
     * 换题时这个上下文跟着换——用户不需要手动贴任何东西。
     */
    fun of(
        q: Question,
        myAnswer: Int?,
        hasSubmitted: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("【当前题目】\n")
        sb.append("题型：").append(com.drone.quiz.data.repo.QuestionTypes.label(q.type)).append("\n")
        sb.append("题干：").append(q.text.trim()).append("\n")
        if (q.options.isNotEmpty()) {
            sb.append("选项：\n")
            q.options.forEachIndexed { i, o ->
                sb.append("  ").append(optionLabel(i, q.isJudge)).append(". ").append(o.trim()).append("\n")
            }
        }
        sb.append("正确答案：").append(q.correctAnswerText()).append("\n")
        if (hasSubmitted && myAnswer != null) {
            sb.append("我的作答：").append(answerLetters(q, myAnswer)).append("\n")
        }
        if (q.explanation.isNotBlank()) {
            sb.append("题库自带解析：").append(q.explanation.trim()).append("\n")
        }
        sb.append("""
            接下来用户会围绕这道题提问。请：
            · 用中文回答，像一位耐心的老师；
            · 先给结论，再讲道理，别绕；
            · 讲清背后的知识点，让用户下次遇到同类题能做对；
            · 控制在几句话到一小段，别写长篇大论；
            · 不知道就直说，别硬编。
        """.trimIndent())
        return sb.toString()
    }

    /** 把位掩码渲染成 A、B、C。未作答返回空串。 */
    fun answerLetters(q: Question, mask: Int): String =
        (0 until q.options.size.coerceAtLeast(31))
            .filter { mask and (1 shl it) != 0 }
            .joinToString("、") { optionLabel(it, q.isJudge) }
}
