/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

data class PersonalSticker(val url: String, val label: String, val info: ImageInfo)

@SingleIn(SessionScope::class)
@Inject
class PersonalStickerStore(
    private val client: MatrixClient,
    private val dispatchers: CoroutineDispatchers,
) {
    private val mutex = Mutex()
    private val pendingImages by lazy { JSONObject() }
    private val mutableStickers = MutableStateFlow<List<PersonalSticker>>(emptyList())
    val stickers = mutableStickers.asStateFlow()

    suspend fun refresh(): Result<Unit> = runCatchingExceptions {
        withContext(dispatchers.io) {
            mutex.withLock { publish(readPack().images()) }
        }
    }

    suspend fun importImages(resolver: ContentResolver, uris: List<Uri>): Result<Unit> = runCatchingExceptions {
        withContext(dispatchers.io) {
            val entries = uris.distinct().map { uri ->
                val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readStickerBytes() }
                upload(title ?: DEFAULT_LABEL, bytes)
            }
            addAll(entries)
        }
    }

    suspend fun saveImage(source: MediaSource, label: String, info: ImageInfo): Result<Unit> = runCatchingExceptions {
        withContext(dispatchers.io) {
            val encrypted = source.json?.let { json ->
                runCatching { JSONObject(json) }.getOrNull()?.let { it.has("file") || it.has("key") }
            } == true
            val sticker = if (encrypted) {
                require((info.size ?: 0) <= MAX_IMAGE_BYTES)
                upload(label, client.matrixMediaLoader.loadMediaContent(source).getOrThrow())
            } else {
                PersonalSticker(source.safeUrl, label.ifBlank { DEFAULT_LABEL }, info)
            }
            addAll(listOf(sticker))
        }
    }

    private suspend fun upload(label: String, bytes: ByteArray): PersonalSticker {
        require(bytes.size in 1..MAX_IMAGE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val mimeType = requireNotNull(bounds.outMimeType).also { require(it.startsWith("image/")) }
        return PersonalSticker(
            url = client.uploadMedia(mimeType, bytes).getOrThrow(),
            label = label.ifBlank { DEFAULT_LABEL },
            info = ImageInfo(
                height = bounds.outHeight.toLong().takeIf { it > 0 },
                width = bounds.outWidth.toLong().takeIf { it > 0 },
                mimetype = mimeType,
                size = bytes.size.toLong(),
                thumbnailInfo = null,
                thumbnailSource = null,
                blurhash = null,
            ),
        )
    }

    private suspend fun addAll(stickers: List<PersonalSticker>) = mutex.withLock {
        val pack = readPack()
        val images = pack.images()
        val urls = images.keys().asSequence().mapNotNull { images.optJSONObject(it)?.optString("url") }.toMutableSet()
        val additions = stickers.filter { it.url.startsWith("mxc://") && urls.add(it.url) }.associate { sticker ->
            val key = "sticker_" + UUID.randomUUID().toString().replace("-", "")
            key to JSONObject()
                .put("body", sticker.label)
                .put("url", sticker.url)
                .put("usage", JSONArray(listOf("sticker")))
                .put(
                    "info",
                    JSONObject().put("w", sticker.info.width).put("h", sticker.info.height)
                        .put("mimetype", sticker.info.mimetype).put("size", sticker.info.size)
                )
        }
        if (additions.isNotEmpty()) {
            additions.forEach { (key, value) -> images.put(key, value) }
            pack.put("images", images)
            client.setAccountData(PERSONAL_PACK_EVENT, pack.toString()).getOrThrow()
            additions.forEach { (key, value) -> pendingImages.put(key, value) }
        }
        publish(images)
    }

    private suspend fun readPack(): JSONObject {
        val content = client.getAccountData(PERSONAL_PACK_EVENT).getOrThrow()?.let(::JSONObject) ?: JSONObject()
        val images = content.images()
        pendingImages.keys().asSequence().toList().forEach { key ->
            if (images.has(key)) pendingImages.remove(key) else images.put(key, pendingImages.get(key))
        }
        return content.put("images", images)
    }

    private fun publish(images: JSONObject) {
        mutableStickers.value = images.keys().asSequence().mapNotNull { key ->
            val entry = images.optJSONObject(key) ?: return@mapNotNull null
            val url = entry.optString("url").takeIf { it.startsWith("mxc://") } ?: return@mapNotNull null
            val usage = entry.optJSONArray("usage")
            if (usage != null && usage.length() > 0 && (0 until usage.length()).none { usage.optString(it) == "sticker" }) {
                return@mapNotNull null
            }
            val info = entry.optJSONObject("info") ?: JSONObject()
            PersonalSticker(
                url = url,
                label = entry.optString("body").ifBlank { key },
                info = ImageInfo(
                    height = info.optLong("h").takeIf { it > 0 },
                    width = info.optLong("w").takeIf { it > 0 },
                    mimetype = info.optString("mimetype").ifBlank { null },
                    size = info.optLong("size").takeIf { it > 0 },
                    thumbnailInfo = null,
                    thumbnailSource = null,
                    blurhash = null,
                ),
            )
        }.distinctBy { it.url }.toList().asReversed()
    }

    private fun JSONObject.images() = optJSONObject("images") ?: JSONObject()

    private companion object {
        const val PERSONAL_PACK_EVENT = "im.ponies.user_emotes"
        const val DEFAULT_LABEL = "Sticker"
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    }

    private fun InputStream.readStickerBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            require(bytes.size() + count <= MAX_IMAGE_BYTES)
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }
}
