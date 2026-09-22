package com.kura.aria.chat

/** Hides tagged reasoning even when a tag arrives across several streamed chunks. */
class VisibleReplyFilter {
    private val pending = StringBuilder()
    private val visible = StringBuilder()
    private var depth = 0
    private val open = "<think>"
    private val close = "</think>"

    fun append(chunk: String): String {
        pending.append(chunk)
        drain()
        return visible.toString().trim()
    }

    fun finish(): String {
        drain()
        // Any pending suffix is an incomplete tag. Never reveal an unfinished block.
        pending.clear()
        return visible.toString().trim()
    }

    private fun drain() {
        while (pending.isNotEmpty()) {
            val text = pending.toString()
            when {
                text.startsWith(open, ignoreCase = true) -> {
                    depth++
                    pending.delete(0, open.length)
                }
                text.startsWith(close, ignoreCase = true) -> {
                    if (depth > 0) depth--
                    pending.delete(0, close.length)
                }
                open.startsWith(text, ignoreCase = true) || close.startsWith(text, ignoreCase = true) -> return
                else -> {
                    if (depth == 0) visible.append(pending[0])
                    pending.deleteCharAt(0)
                }
            }
        }
    }
}
