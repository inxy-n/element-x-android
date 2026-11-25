/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import android.content.ClipData

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.launch

@Composable
internal fun MessageFullScreenPreview(
    onDismiss: () -> Unit,
    copyText: String?,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FullScreenMessageContent(onDismiss, copyText, content)
    }
}

@Composable
private fun FullScreenMessageContent(onDismiss: () -> Unit, copyText: String?, content: @Composable () -> Unit) {
    val currentLocale = LocalConfiguration.current.locales[0]
    val localeTag = remember(copyText, currentLocale) {
        val text = copyText.orEmpty()
        val usesChinese = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } && text.none {
            when (Character.UnicodeScript.of(it.code)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
                else -> false
            }
        }
        if (usesChinese && currentLocale.language != "zh") "zh-Hans" else currentLocale.toLanguageTag()
    }
    val textStyle = ElementTheme.typography.fontBodyLgRegular.copy(fontSize = 22.sp, lineHeight = 34.sp, localeList = LocaleList(localeTag))
    Column(
        modifier = Modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp)) {
            if (copyText != null) {
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                val copyLabel = stringResource(CommonStrings.action_copy_text)
                IconButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, copyText))) }
                }) {
                    Icon(CompoundIcons.Copy(), contentDescription = copyLabel)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(CompoundIcons.Close(), contentDescription = stringResource(CommonStrings.action_close))
            }
        }
        Box(
            modifier = Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth(),
            contentAlignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.08f),
        ) {
            Box(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalTextStyle provides textStyle) {
                    content()
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun MessageFullScreenPreviewPreview() = ElementPreview {
    FullScreenMessageContent(onDismiss = {}, copyText = "Here is the full message, with room to read on a small screen.") {
        Text("Here is the full message, with room to read on a small screen.")
    }
}

@PreviewsDayNight
@Composable
internal fun LongMessageFullScreenPreviewPreview() = ElementPreview {
    FullScreenMessageContent(onDismiss = {}, copyText = null) {
        Text(List(30) { "A longer message can be read by scrolling the preview." }.joinToString("\n\n"))
    }
}

/** Recognizes a second tap even when an interactive child handled the first tap. */
internal fun Modifier.messageDoubleTap(onDoubleTap: (() -> Unit)?): Modifier {
    if (onDoubleTap == null) return this
    return pointerInput(onDoubleTap) {
        var previousUp = Long.MIN_VALUE
        var previousPosition = Offset.Unspecified
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val lastUp = previousUp
            previousUp = Long.MIN_VALUE
            val gap = if (lastUp == Long.MIN_VALUE) Long.MAX_VALUE else down.uptimeMillis - lastUp
            val isSecondTap = gap in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - previousPosition).getDistance() < viewConfiguration.touchSlop * 2
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (event.changes.size != 1 || (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                ) {
                    return@awaitEachGesture
                }
                if (!change.pressed) {
                    if (isSecondTap) {
                        change.consume()
                        onDoubleTap()
                    } else {
                        previousPosition = change.position
                        previousUp = change.uptimeMillis
                    }
                    break
                }
            }
        }
    }
}
