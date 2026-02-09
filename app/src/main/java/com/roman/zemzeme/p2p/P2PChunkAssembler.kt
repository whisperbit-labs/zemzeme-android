package com.roman.zemzeme.p2p

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Reassembles multi-chunk P2P media transfers.
 * Mirrors BLE FragmentManager logic with a 30-second timeout.
 */
class P2PChunkAssembler {

    companion object {
        private const val TAG = "P2PChunkAssembler"
        private const val TIMEOUT_MS = 30_000L
        private const val CLEANUP_INTERVAL_MS = 10_000L
    }

    private data class ChunkState(
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        val contentType: String,
        val fileName: String,
        val totalChunks: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Completed result tuple: (raw bytes, contentType, fileName)
    data class AssembledMedia(val bytes: ByteArray, val contentType: String, val fileName: String)

    private val pending = ConcurrentHashMap<String, ChunkState>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    init {
        scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanup()
            }
        }
    }

    /**
     * Add a received chunk.
     * @return AssembledMedia when all chunks have arrived; null otherwise.
     */
    fun addChunk(
        chunkId: String,
        chunkIndex: Int,
        totalChunks: Int,
        contentType: String,
        fileName: String,
        chunkData: ByteArray
    ): AssembledMedia? {
        val state = pending.getOrPut(chunkId) {
            ChunkState(contentType = contentType, fileName = fileName, totalChunks = totalChunks)
        }

        synchronized(state.chunks) {
            state.chunks[chunkIndex] = chunkData
            Log.d(TAG, "Chunk $chunkIndex/$totalChunks received for $chunkId")

            if (state.chunks.size == totalChunks) {
                // Reassemble in index order
                val result = (0 until totalChunks).mapNotNull { i -> state.chunks[i] }
                if (result.size < totalChunks) {
                    Log.w(TAG, "Missing chunks for $chunkId")
                    return null
                }
                val assembled = result.reduce { acc, bytes -> acc + bytes }
                pending.remove(chunkId)
                Log.d(TAG, "Reassembled ${assembled.size} bytes for $chunkId")
                return AssembledMedia(assembled, state.contentType, state.fileName)
            }
        }
        return null
    }

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - TIMEOUT_MS
        val expired = pending.entries.filter { it.value.timestamp < cutoff }.map { it.key }
        expired.forEach { key ->
            pending.remove(key)
            Log.d(TAG, "Expired incomplete chunk transfer: $key")
        }
    }

    fun shutdown() {
        job.cancel()
    }
}
