package com.kura.aria.memory

data class Memory(
        val id: Long,
            val content: String,
                val category: String = "general",
                    val importance: Int = 1
)

object AriaMemory {
        private val memories = mutableListOf<Memory>()
            private var nextId = 1L

                fun remember(text: String): Memory {
                            val memory = Memory(nextId++, text)
                                    memories.add(memory)
                                            return memory
                }

                    fun recallAll(): List<Memory> = memories.toList()

                        fun forget(id: Long): Boolean =
                                memories.removeAll { it.id == id }

                                    fun count(): Int = memories.size
}