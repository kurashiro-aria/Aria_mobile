// Temporary source patch applied during Gradle configuration for ARIA 0.2.18.
// Idempotent: once markers are present it leaves MainActivity unchanged.
val main = file("src/main/java/com/kura/aria/MainActivity.kt")
var source = main.readText()

if (!source.contains("val chatStage = FrameLayout")) {
    val old = """        // Give the expression its own space above the conversation.
        root.addView(avatarCard, LinearLayout.LayoutParams(dp(150), dp(200)).apply {
            marginStart = dp(4); topMargin = dp(8)
        })

        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        scroll = ScrollView(this).apply { addView(conversation); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
"""
    val replacement = """        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        scroll = ScrollView(this).apply { addView(conversation); isFillViewport = true }
        val chatStage = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(avatarCard, FrameLayout.LayoutParams(dp(150), dp(200), Gravity.TOP or Gravity.START).apply {
                marginStart = dp(4); topMargin = dp(8)
            })
        }
        avatarCard.elevation = dp(10).toFloat()
        root.addView(chatStage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
"""
    check(source.contains(old)) { "ARIA portrait layout target not found" }
    source = source.replace(old, replacement)
}

if (!source.contains("val all = chatHistory.readAll()")) {
    val old = """                withContext(Dispatchers.IO) { chatHistory.append(\"Kura\", message) }
                val filter = VisibleReplyFilter()
                val modelMessage = withContext(Dispatchers.IO) {
                    val relevant = ariaMemory.relevantTo(message)
"""
    val replacement = """                withContext(Dispatchers.IO) { chatHistory.append(\"Kura\", message) }
                val filter = VisibleReplyFilter()
                val modelMessage = withContext(Dispatchers.IO) {
                    val all = chatHistory.readAll()
                    engine.setSystemPrompt(AriaPersonality.promptWithRecentConversation(all))
                    val relevant = ariaMemory.relevantTo(message)
"""
    check(source.contains(old)) { "ARIA conversation context target not found" }
    source = source.replace(old, replacement)
}

main.writeText(source)
