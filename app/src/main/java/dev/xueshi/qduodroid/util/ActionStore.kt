package dev.xueshi.qduodroid.util

import android.content.Context
import dev.xueshi.qduodroid.engine.QDuoPrompts
import dev.xueshi.qduodroid.model.ActionKind
import dev.xueshi.qduodroid.model.QDuoAction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 保存用户的动作列表（JSON，存在 SharedPreferences 里）。
 *
 * 第一次读取时还没有保存过列表，就用 [ActionListCodec.defaultActions] 生成默认的六个动作并立即存下来。
 * 读过一次之后列表缓存在内存里，悬浮条每次弹出时直接用缓存；[save] 会同时更新缓存。
 * 悬浮条、结果卡片和主界面都在同一个进程里，所以不需要跨进程同步。
 */
object ActionStore {

    private const val PREFS_NAME = "qduo_prefs"
    private const val KEY_ACTIONS = "actions_json_v1"

    @Volatile
    private var cache: List<QDuoAction>? = null

    fun load(context: Context): List<QDuoAction> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_ACTIONS, null)
            val list = if (raw != null) {
                ActionListCodec.decode(raw) ?: ActionListCodec.defaultActions(llmTranslate = false)
            } else {
                // 旧版本有一个「翻译也优先使用此大模型」的开关。打开过并且配好了模型的用户，
                // 默认的「翻译」动作做成 AI 动作，保持他们原来的翻译效果。
                val appPrefs = AppPrefs(context)
                val seeded = ActionListCodec.defaultActions(
                    llmTranslate = appPrefs.useLlmForTranslate && appPrefs.hasLlmConfig(),
                )
                prefs.edit().putString(KEY_ACTIONS, ActionListCodec.encode(seeded)).apply()
                seeded
            }
            cache = list
            return list
        }
    }

    fun save(context: Context, actions: List<QDuoAction>) {
        synchronized(this) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIONS, ActionListCodec.encode(actions))
                .apply()
            cache = actions.toList()
        }
    }

    fun resetToDefaults(context: Context): List<QDuoAction> {
        val defaults = ActionListCodec.defaultActions(llmTranslate = false)
        save(context, defaults)
        return defaults
    }

    /** 悬浮条上显示的动作：没有被关掉的那些，按列表顺序。 */
    fun enabledActions(context: Context): List<QDuoAction> = load(context).filter { it.enabled }

    fun newId(): String = "a_" + UUID.randomUUID().toString().replace("-", "").take(10)
}

/** 动作列表和 JSON 之间的转换。不依赖 Android，可以直接写单元测试。 */
object ActionListCodec {

    private const val SCHEMA_VERSION = 1

    fun defaultActions(llmTranslate: Boolean): List<QDuoAction> = listOf(
        if (llmTranslate) {
            QDuoAction("translate", "翻译", ActionKind.AI, prompt = QDuoPrompts.TRANSLATE_PROMPT)
        } else {
            QDuoAction("translate", "翻译", ActionKind.TRANSLATE)
        },
        QDuoAction("polish", "润色", ActionKind.AI, prompt = QDuoPrompts.POLISH_PROMPT),
        QDuoAction("summarize", "总结", ActionKind.AI, prompt = QDuoPrompts.SUMMARIZE_PROMPT),
        QDuoAction("explain", "解释", ActionKind.AI, prompt = QDuoPrompts.EXPLAIN_PROMPT),
        QDuoAction(
            "search",
            "搜索",
            ActionKind.OPEN_URL,
            url = "https://www.google.com/search?q=${QDuoAction.TEXT_PLACEHOLDER}",
        ),
        QDuoAction("speak", "朗读", ActionKind.SPEAK),
    )

    fun encode(actions: List<QDuoAction>): String {
        val arr = JSONArray()
        for (a in actions) {
            val obj = JSONObject()
                .put("v", SCHEMA_VERSION)
                .put("id", a.id)
                .put("title", a.title)
                .put("kind", a.kind.id)
                .put("enabled", a.enabled)
            if (a.prompt.isNotEmpty()) obj.put("prompt", a.prompt)
            if (a.url.isNotEmpty()) obj.put("url", a.url)
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * 解析保存的 JSON。整段 JSON 坏掉时返回 null；某一项的类型是这个版本不认识的，就跳过那一项。
     * id 重复的项只保留第一个，避免列表里两项互相覆盖。
     */
    fun decode(raw: String): List<QDuoAction>? {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val seenIds = HashSet<String>()
        val out = ArrayList<QDuoAction>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val kind = ActionKind.fromId(obj.optString("kind")) ?: continue
            val id = obj.optString("id").ifBlank { ActionStore.newId() }
            if (!seenIds.add(id)) continue
            out += QDuoAction(
                id = id,
                title = obj.optString("title").ifBlank { kind.label },
                kind = kind,
                prompt = obj.optString("prompt"),
                url = obj.optString("url"),
                enabled = obj.optBoolean("enabled", true),
            )
        }
        return out
    }
}
