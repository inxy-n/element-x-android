/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import android.os.Build
import android.text.SpannedString
import android.util.TypedValue
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.timeline.LocalTimelineEventRendererConfig
import io.element.android.features.messages.impl.timeline.model.event.AN_EMOJI_ONLY_TEXT
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContentPreviewParam
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.utils.containsOnlyEmojis
import io.element.android.libraries.androidutils.text.LinkifyHelper
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.utils.LocalUiTestMode
import io.element.android.libraries.htmlrenderer.api.MentionNodeContent
import io.element.android.libraries.htmlrenderer.api.ParagraphNode
import io.element.android.libraries.htmlrenderer.api.ui.HtmlMessageContent
import io.element.android.libraries.textcomposer.ElementRichTextEditorStyle
import io.element.android.libraries.textcomposer.mentions.LocalMentionSpanUpdater
import io.element.android.libraries.textcomposer.mentions.limitMentionPillWidth
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayout
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayoutData
import io.element.android.wysiwyg.compose.EditorStyledText
import io.element.android.wysiwyg.link.Link
import java.util.Locale

@Composable
fun TimelineItemTextView(
    content: TimelineItemTextBasedContent,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
) {
    // The View <-> Compose interop is not working well with Compose UI tests (it loops indefinitely), so we skip it in the UI test mode.
    if (LocalUiTestMode.current) return

    // When the new timeline event renderer is enabled and the message has a parsed tree, render it
    // natively with Compose. Otherwise fall back to the legacy rich text editor view below.
    val messageTree = content.messageTree
    val nativePreviewText = remember(content, selectable) {
        if (!selectable || content.formattedBody.toString() != content.body) return@remember null
        val unstyled = messageTree?.children?.all { block ->
            block is ParagraphNode && block.inlineContent.isEmpty() && block.text.spanStyles.isEmpty() &&
                block.text.getStringAnnotations(0, block.text.length).isEmpty() &&
                block.text.getLinkAnnotations(0, block.text.length).isEmpty()
        } ?: (content.htmlDocument == null)
        content.body.takeIf { unstyled }
    }
    if (nativePreviewText != null && !LocalInspectionMode.current) {
        NativePreviewText(text = nativePreviewText, modifier = modifier)
        return
    }
    if (LocalTimelineEventRendererConfig.current.isComposeRendererEnabled && messageTree != null) {
        Box(modifier.semantics { contentDescription = content.plainText }) {
            HtmlMessageContent(
                node = messageTree,
                currentUserId = LocalTimelineEventRendererConfig.current.currentUserId,
                onLinkClick = { url, text -> onLinkClick(Link(url = url, text = text)) },
                onLinkLongClick = { url, text -> onLinkLongClick(Link(url = url, text = text)) },
                onMentionClick = { mention ->
                    when (mention) {
                        is MentionNodeContent.Room -> onLinkClick(Link(url = mention.permalinkUrl, text = mention.displayText))
                        is MentionNodeContent.User -> onLinkClick(Link(url = mention.permalinkUrl, text = mention.displayText))
                        is MentionNodeContent.Everyone -> { /* No action for @room mentions */ }
                    }
                },
                onContentLayoutChange = { data ->
                    onContentLayoutChange(
                        ContentAvoidingLayoutData(
                            contentWidth = data.contentWidth,
                            contentHeight = data.contentHeight,
                            nonOverlappingContentWidth = data.nonOverlappingContentWidth,
                            nonOverlappingContentHeight = data.nonOverlappingContentHeight,
                        )
                    )
                },
                selectable = selectable,
            )
        }
        return
    }

    val isInPreview = LocalInspectionMode.current
    val emojiOnly = remember(content.body, content.formattedBody, isInPreview) {
        content.formattedBody.toString() == content.body &&
            content.body.replace(" ", "").let { body ->
                if (isInPreview) body == AN_EMOJI_ONLY_TEXT else body.containsOnlyEmojis()
            }
    }
    val textStyle = when {
        selectable -> LocalTextStyle.current
        emojiOnly -> ElementTheme.typography.fontHeadingXlRegular
        else -> ElementTheme.typography.fontBodyLgRegular
    }
    CompositionLocalProvider(
        LocalContentColor provides ElementTheme.colors.textPrimary,
        LocalTextStyle provides textStyle
    ) {
        val text = getTextWithResolvedMentions(content)
        Box(
            modifier
                .semantics { contentDescription = content.plainText }
                .limitMentionPillWidth(text)
        ) {
            if (selectable && !isInPreview) {
                SelectionContainer {
                    androidx.compose.material3.Text(
                        text = content.body,
                        onTextLayout = ContentAvoidingLayout.measureLastTextLine(onContentLayoutChange = onContentLayoutChange),
                    )
                }
            } else {
                EditorStyledText(
                    text = text,
                    onLinkClickedListener = onLinkClick,
                    onLinkLongClickedListener = onLinkLongClick,
                    style = ElementRichTextEditorStyle.textStyle(fontScale = LocalTimelineEventRendererConfig.current.fontScale),
                    onTextLayout = ContentAvoidingLayout.measureLegacyLastTextLine(onContentLayoutChange = onContentLayoutChange),
                    releaseOnDetach = false,
                )
            }
        }
    }
}

/** The platform handles word selection and tolerates a small amount of vertical drift when dragging a handle. */
@Composable
private fun NativePreviewText(text: String, modifier: Modifier = Modifier) {
    val style = LocalTextStyle.current
    val locale = style.localeList?.firstOrNull()?.toLanguageTag()?.let(Locale::forLanguageTag) ?: Locale.getDefault()
    val color = ElementTheme.colors.textPrimary.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextIsSelectable(true)
                includeFontPadding = false
                setLineSpacing(0f, 1.35f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setTextClassifier(TextClassifier.NO_OP)
            }
        },
        update = { view ->
            view.textLocale = locale
            view.setTextColor(color)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (style.fontSize.isSp) style.fontSize.value else 22f)
            if (view.text.toString() != text) view.text = text
        },
    )
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
@Composable
internal fun getTextWithResolvedMentions(content: TimelineItemTextBasedContent): CharSequence {
    val mentionSpanUpdater = LocalMentionSpanUpdater.current
    val bodyWithResolvedMentions = mentionSpanUpdater.rememberMentionSpans(content.formattedBody)
    return SpannedString.valueOf(bodyWithResolvedMentions)
}

@PreviewsDayNight
@Composable
internal fun TimelineItemTextViewPreview(
    @PreviewParameter(TimelineItemTextBasedContentPreviewParam::class) content: TimelineItemTextBasedContent
) = ElementPreview {
    TimelineItemTextView(
        content = content,
        onLinkClick = {},
        onLinkLongClick = {},
    )
}

@Preview
@Composable
internal fun TimelineItemTextViewWithLinkifiedUrlPreview() = ElementPreview {
    val content = aTimelineItemTextContent(
        formattedBody = LinkifyHelper.linkify("The link should end after the first '?' (url: github.com/element-hq/element-x-android/README?)?.")
    )
    TimelineItemTextView(
        content = content,
        onLinkClick = {},
        onLinkLongClick = {},
    )
}

@Preview
@Composable
internal fun TimelineItemTextViewWithLinkifiedUrlAndNestedParenthesisPreview() = ElementPreview {
    val content = aTimelineItemTextContent(
        formattedBody = LinkifyHelper.linkify("The link should end after the '(ME)' ((url: github.com/element-hq/element-x-android/READ(ME)))!")
    )
    TimelineItemTextView(
        content = content,
        onLinkClick = {},
        onLinkLongClick = {},
    )
}
