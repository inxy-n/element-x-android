/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrixmedia.api.MediaRequestData

data class StickerPickerActions(
    val store: PersonalStickerStore,
    val onImport: (ContentResolver, List<Uri>) -> Unit,
    val onSend: (PersonalSticker) -> Unit,
)

@Composable
internal fun PersonalStickerPicker(actions: StickerPickerActions) {
    val resolver = LocalContext.current.contentResolver
    val stickers by actions.store.stickers.collectAsState()
    val importImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) actions.onImport(resolver, uris)
    }
    LaunchedEffect(actions.store) { actions.store.refresh() }
    PersonalStickerGrid(
        stickers = stickers,
        onImport = { importImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSend = actions.onSend,
    )
}

@Composable
private fun PersonalStickerGrid(
    stickers: List<PersonalSticker>,
    onImport: () -> Unit,
    onSend: (PersonalSticker) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(60.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ElementTheme.colors.bgSubtleSecondary)
                    .clickable(onClick = onImport),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CompoundIcons.Plus(),
                    contentDescription = stringResource(R.string.action_import_stickers),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        items(stickers, key = { it.url }) { sticker ->
            AsyncImage(
                model = MediaRequestData(MediaSource(sticker.url), MediaRequestData.Kind.File(sticker.label, sticker.info.mimetype ?: "image/*")),
                contentDescription = sticker.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSend(sticker) }
                    .padding(4.dp),
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun PersonalStickerGridPreview() = ElementPreview {
    PersonalStickerGrid(stickers = emptyList(), onImport = {}, onSend = {})
}
