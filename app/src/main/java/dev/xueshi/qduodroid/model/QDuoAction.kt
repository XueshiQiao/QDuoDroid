package dev.xueshi.qduodroid.model

enum class QDuoAction(
    val title: String,
    val badge: String,
    val isSheetAction: Boolean,
) {
    TRANSLATE("翻译", "中英互译", true),
    POLISH("润色", "表达优化", true),
    SUMMARIZE("总结", "要点提炼", true),
    EXPLAIN("解释", "概念拆解", true),
    SEARCH("搜索", "网页搜索", false),
    SPEAK("朗读", "系统语音", false),
    ;

    companion object {
        val sheetActions: List<QDuoAction> = entries.filter { it.isSheetAction }
    }
}
