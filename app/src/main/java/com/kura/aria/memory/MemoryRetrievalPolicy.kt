package com.kura.aria.memory

/** Hard bounds applied before scoring so storage growth never becomes a full-table RAM load. */
object MemoryRetrievalPolicy {
    const val MAX_CANDIDATES = 60
    const val MAX_QUERY_TERMS = 12
    const val SUMMARY_GROUP_SIZE = 50

    fun clampCandidateLimit(requested: Int): Int = requested.coerceIn(1, MAX_CANDIDATES)

    /**
     * A lazy helper used by alternative/future indexes. It intentionally stops consuming the
     * source once the bounded candidate window has been produced.
     */
    fun <T> candidateWindow(source: Sequence<T>, requested: Int = MAX_CANDIDATES): List<T> =
        source.take(clampCandidateLimit(requested)).toList()

    fun shouldConsolidate(unsummarizedCount: Long): Boolean =
        unsummarizedCount >= SUMMARY_GROUP_SIZE
}
