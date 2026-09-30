package com.kura.aria.memory

import android.content.Context
import com.kura.aria.chat.ChatHistory
import com.kura.aria.chat.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, encrypted, streaming backup. Provider credentials are never part of the format. */
class AriaMemoryBackup(private val context: Context, private val memory: AriaMemory,
                       private val history: ChatHistory) {
    fun export(directory: File, passphrase: CharArray): File {
        require(passphrase.size >= 8) { "La contraseña debe tener al menos 8 caracteres" }
        directory.mkdirs()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val file = File(directory, "ARIA_MEMORY_BACKUP_$date.aria")
        val salt=ByteArray(16).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase,salt)) }
        file.outputStream().buffered().use { raw ->
            raw.write(MAGIC); raw.write(salt.size); raw.write(salt); raw.write(cipher.iv.size); raw.write(cipher.iv)
            ZipOutputStream(CipherOutputStream(raw, cipher)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(JSONObject().put("schema", SCHEMA).put("createdAt", System.currentTimeMillis())
                    .put("memoryCount", memory.count()).put("historyCount", history.count()).toString().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("memories.jsonl")); writeMemory(zip); zip.closeEntry()
                zip.putNextEntry(ZipEntry("history.jsonl")); writeHistory(zip); zip.closeEntry()
            }
        }
        return file
    }

    fun importBackup(file: File, passphrase: CharArray): ImportResult {
        validate(file,passphrase)
        var memories = 0; var messages = 0
        open(file,passphrase).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    "memories.jsonl" -> readEntryLines(zip) { line ->
                        memory.restore(memory(JSONObject(line))); memories++
                    }
                    "history.jsonl" -> readEntryLines(zip) { line ->
                        val json=JSONObject(line); history.appendIfMissing(ChatMessage(json.getString("role"),
                            json.getString("text"),json.getLong("timestamp"))); messages++
                    }
                }
            }
        }
        return ImportResult(memories, messages)
    }

    fun validate(file: File, passphrase: CharArray) {
        var manifestSeen=false
        open(file,passphrase).use { zip -> while (true) {
            val entry=zip.nextEntry?:break
            when(entry.name) {
                "manifest.json" -> { val json=JSONObject(readEntryText(zip))
                    require(json.getInt("schema")==SCHEMA) { "Backup incompatible" }; manifestSeen=true }
                "memories.jsonl" -> readEntryLines(zip) { memory(JSONObject(it)) }
                "history.jsonl" -> readEntryLines(zip) { line ->
                    val json=JSONObject(line); require(json.getString("role") in setOf("Kura","ARIA")); json.getString("text"); json.getLong("timestamp") }
            }
        } }
        require(manifestSeen) { "Backup sin manifiesto" }
    }

    private fun writeMemory(output: OutputStream) {
        val writer=output.bufferedWriter(); var offset=0
        do { val page=memory.page(offset,250); page.forEach { writer.append(json(it).toString()).append('\n') }
            writer.flush(); offset+=page.size } while(page.size==250)
    }
    private fun writeHistory(output: OutputStream) {
        val writer=output.bufferedWriter(); var offset=0
        do { val page=history.page(offset,500); page.forEach { writer.append(JSONObject()
            .put("role",it.role).put("text",it.text).put("timestamp",it.timestamp).toString()).append('\n') }
            writer.flush(); offset+=page.size } while(page.size==500)
    }
    private fun json(m: Memory)=JSONObject().put("id",m.id).put("type",m.category).put("content",m.content)
        .put("createdAt",m.timestamp).put("updatedAt",m.updatedAt).put("lastUsed",m.lastUsed)
        .put("importance",m.importance).put("confidence",m.confidence.toDouble()).put("source",m.source)
        .put("tags",JSONArray(m.tags)).put("entities",JSONArray(m.entities)).put("conversationId",m.conversationId)
        .put("schema",m.schemaVersion).put("active",m.active).put("accessCount",m.accessCount)
    private fun memory(j:JSONObject)=Memory(j.optLong("id"),j.getString("content"),j.optString("type","personal"),
        j.optInt("importance",1),j.optLong("createdAt"),j.optString("source","Kura"),jsonList(j.optJSONArray("tags")),
        j.optLong("updatedAt"),j.optBoolean("active",true),j.optLong("lastUsed"),j.optInt("accessCount"),
        j.optDouble("confidence",1.0).toFloat(),jsonList(j.optJSONArray("entities")),j.optString("conversationId").takeIf(String::isNotBlank),j.optInt("schema",1))
    private fun jsonList(a:JSONArray?)=if(a==null) emptyList() else (0 until a.length()).mapNotNull{a.optString(it).takeIf(String::isNotBlank)}
    private fun readEntryLines(zip: ZipInputStream, consume: (String)->Unit) {
        val reader=NonClosingInputStream(zip).bufferedReader()
        while(true) consume(reader.readLine() ?: break)
    }
    private fun readEntryText(zip: ZipInputStream):String = buildString {
        readEntryLines(zip) { append(it).append('\n') }
    }

    private fun open(file:File,passphrase:CharArray):ZipInputStream {
        val raw=file.inputStream().buffered(); val magic=ByteArray(MAGIC.size); require(raw.read(magic)==MAGIC.size&&magic.contentEquals(MAGIC)){"Backup inválido"}
        val saltSize=raw.read(); require(saltSize in 16..32); val salt=ByteArray(saltSize); require(raw.read(salt)==saltSize)
        val ivSize=raw.read(); require(ivSize in 12..32); val iv=ByteArray(ivSize); require(raw.read(iv)==ivSize)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(passphrase,salt),GCMParameterSpec(128,iv))}
        return ZipInputStream(CipherInputStream(raw,cipher))
    }
    private fun key(passphrase:CharArray,salt:ByteArray)=SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(passphrase,salt,210_000,256)).encoded,"AES")
    data class ImportResult(val memories:Int,val messages:Int)
    private class NonClosingInputStream(input:InputStream):FilterInputStream(input){override fun close()=Unit}
    companion object { private const val SCHEMA=1; private val MAGIC="ARIAMEM1".toByteArray() }
}
