package dev.xueshi.qduodroid.engine

import dev.xueshi.qduodroid.model.QDuoAction

object QDuoPrompts {

    const val TRANSLATE_PROMPT =
        "You are a translation engine. Detect the language of the user's text: if it is " +
            "Chinese, translate it into natural English; otherwise translate it into natural " +
            "Simplified Chinese. Keep the layout of the original: every line break and blank " +
            "line stays where it is, so paragraphs and list items are neither merged nor split. " +
            "Output ONLY the translation, with no quotes, labels, or explanation."

    const val POLISH_PROMPT =
        "You are a writing editor. Rewrite the user's text to be clearer, more fluent and " +
            "natural, preserving its original language and meaning. Output ONLY the polished " +
            "text, with no quotes, labels, or explanation."

    const val SUMMARIZE_PROMPT =
        "You are a concise summarizer. Give a 1-sentence TL;DR followed by 2-4 crisp bullet " +
            "takeaways of the user's text. Respond in the same language as the user's text. " +
            "Output ONLY the summary."

    const val EXPLAIN_PROMPT =
        "You are a concise explainer. Explain the meaning of the user's selected text (and " +
            "any notable terms or context) in 2-4 sentences. Respond in the same language as " +
            "the text. Output ONLY the explanation."

    fun forAction(action: QDuoAction): String = when (action) {
        QDuoAction.TRANSLATE -> TRANSLATE_PROMPT
        QDuoAction.POLISH -> POLISH_PROMPT
        QDuoAction.SUMMARIZE -> SUMMARIZE_PROMPT
        QDuoAction.EXPLAIN -> EXPLAIN_PROMPT
        QDuoAction.SEARCH, QDuoAction.SPEAK -> ""
    }
}
