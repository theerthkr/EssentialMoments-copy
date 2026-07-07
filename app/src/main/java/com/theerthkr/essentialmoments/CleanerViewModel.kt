package com.theerthkr.essentialmoments

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theerthkr.essentialmoments.ml.EmbeddingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CleanerViewModel"

// A group of photos that are very similar
data class DuplicateGroup(
    val groupIndex: Int,
    val images: List<MediaImage>
)

class CleanerViewModel(application: Application) : AndroidViewModel(application) {

    private val store = EmbeddingStore(application)

    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateGroups: StateFlow<List<DuplicateGroup>> = _duplicateGroups.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun scanForDuplicates(similarityThreshold: Float = 0.95f) {
        if (_isScanning.value) return

        viewModelScope.launch(Dispatchers.Default) {
            _isScanning.value = true
            try {
                val allIds = store.allIndexedIds().toList()
                Log.d(TAG, "Scanning ${allIds.size} images for duplicates")

                val embeddings = allIds.mapNotNull { id ->
                    val emb = store.get(id)
                    if (emb != null) id to emb else null
                }.toMap()

                val processed = mutableSetOf<String>()
                val groups = mutableListOf<DuplicateGroup>()
                var groupCount = 0

                val idsToCompare = embeddings.keys.toList()

                for (i in idsToCompare.indices) {
                    val id1 = idsToCompare[i]
                    if (processed.contains(id1)) continue

                    val currentGroup = mutableListOf<String>()
                    val emb1 = embeddings[id1]!!

                    for (j in i + 1 until idsToCompare.size) {
                        val id2 = idsToCompare[j]
                        if (processed.contains(id2)) continue

                        val emb2 = embeddings[id2]!!
                        val similarity = cosineSimilarity(emb1, emb2)

                        if (similarity >= similarityThreshold) {
                            if (currentGroup.isEmpty()) {
                                currentGroup.add(id1)
                            }
                            currentGroup.add(id2)
                            processed.add(id2)
                        }
                    }

                    if (currentGroup.isNotEmpty()) {
                        processed.add(id1)
                        // Convert IDs to MediaImage objects
                        val mediaImages = currentGroup.mapNotNull { resolveImage(it) }
                        if (mediaImages.size > 1) {
                            groups.add(DuplicateGroup(groupCount++, mediaImages))
                        }
                    }
                }

                Log.d(TAG, "Found ${groups.size} duplicate groups")
                _duplicateGroups.value = groups

            } catch (e: Exception) {
                Log.e(TAG, "Error scanning for duplicates", e)
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) {
            s += a[i] * b[i]
        }
        return s
    }

    private fun resolveImage(imageId: String): MediaImage? = try {
        val id  = imageId.toLong()
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        MediaImage(id, uri.toString(), "", 0L)
    } catch (e: NumberFormatException) { null }

    fun removeDeletedImages(deletedIds: Set<Long>) {
        _duplicateGroups.value = _duplicateGroups.value.mapNotNull { group ->
            val remainingImages = group.images.filterNot { deletedIds.contains(it.id) }
            if (remainingImages.size > 1) {
                group.copy(images = remainingImages)
            } else {
                null
            }
        }
    }
}
