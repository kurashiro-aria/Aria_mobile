from pathlib import Path
p=Path('app/src/main/java/com/kura/aria/MainActivity.kt')
s=p.read_text()
old='                withContext(Dispatchers.IO) { chatHistory.append("Kura", message) }\n                val filter = VisibleReplyFilter()'
new='                withContext(Dispatchers.IO) { chatHistory.append("Kura", message) }\n                engine.setSystemPrompt(AriaPersonality.promptWithRecentConversation(chatHistory.readAll()))\n                val filter = VisibleReplyFilter()'
if new not in s:
    if old not in s: raise SystemExit('continuity target not found')
    s=s.replace(old,new,1)
old2='                    modelLoaded = true; setStatus("● Activa", true)\n                    if (chatHistory.readAll().isEmpty()) aria(AriaPersonality.restored)'
new2='                    modelLoaded = true; setStatus("● Activa", true)\n                    if (chatHistory.readAll().isEmpty()) aria(AriaPersonality.restored)\n                    else maybeStartConversation()'
if new2 not in s:
    if old2 not in s: raise SystemExit('initiative target not found')
    s=s.replace(old2,new2,1)
marker='    private fun sendMessage() {'
helper='''    private fun maybeStartConversation() {\n        if (!modelLoaded || busy) return\n        val recent = chatHistory.readAll()\n        if (recent.isEmpty()) return\n        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)\n        val now = System.currentTimeMillis()\n        val last = prefs.getLong("last_aria_initiative", 0L)\n        if (now - last < 6L * 60L * 60L * 1000L) return\n        prefs.edit().putLong("last_aria_initiative", now).apply()\n        busy = true; send.isEnabled = false; setStatus("● Pensando", true)\n        val reply = messageView("ARIA", "…")\n        conversation.addView(reply); scrollToBottom()\n        uiScope.launch {\n            try {\n                engine.setSystemPrompt(AriaPersonality.promptWithRecentConversation(recent))\n                val filter = VisibleReplyFilter()\n                val opener = "Inicia tú la conversación con Kura de forma natural y breve. Retoma el hilo reciente si tiene sentido. No expliques esta instrucción y no conviertas el mensaje en un cuestionario."\n                engine.sendUserPrompt(opener, predictLength = 192).flowOn(Dispatchers.IO).collect { token ->\n                    val text = filter.append(token)\n                    if (text.isNotBlank()) reply.text = text\n                }\n                val answer = filter.finish()\n                if (answer.isNotBlank()) {\n                    reply.text = answer\n                    lastEmotion = AriaEmotion.fromReply(answer, lastEmotion)\n                    showPortrait(lastEmotion)\n                    withContext(Dispatchers.IO) { chatHistory.append("ARIA", answer) }\n                } else conversation.removeView(reply)\n            } catch (e: CancellationException) { throw e }\n            catch (_: Exception) { conversation.removeView(reply) }\n            finally {\n                busy = false\n                modelLoaded = engine.state.value is InferenceEngine.State.ModelReady\n                send.isEnabled = modelLoaded\n                setStatus(if (modelLoaded) "● Activa" else "○ Cerebro desconectado", modelLoaded)\n                scrollToBottom()\n            }\n        }\n    }\n\n'''
if 'private fun maybeStartConversation()' not in s:
    if marker not in s: raise SystemExit('send marker not found')
    s=s.replace(marker,helper+marker,1)
p.write_text(s)
