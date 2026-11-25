/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.htmlrenderer.api.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.hrm.latex.renderer.model.LatexThemeColors
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.htmlrenderer.api.INLINE_MATH_ANNOTATION
import io.element.android.libraries.htmlrenderer.api.MathBlockNode
import io.element.android.libraries.htmlrenderer.api.mathInlineContentId

@Composable
private fun rememberMathConfig(fontSize: TextUnit): LatexConfig {
    val foreground = ElementTheme.colors.textPrimary
    return remember(fontSize, foreground) {
        val colors = LatexThemeColors(color = foreground, backgroundColor = Color.Transparent)
        LatexConfig(
            fontSize = if (fontSize.isSpecified) fontSize else 16.sp,
            theme = LatexTheme.auto(light = colors, dark = colors),
        )
    }
}

@Composable
internal fun rememberMathInlineContent(text: AnnotatedString, fontSize: TextUnit, availableWidth: Dp): Map<String, InlineTextContent> {
    val formulas = remember(text) {
        text.getStringAnnotations(INLINE_MATH_ANNOTATION, 0, text.length).map { it.item }.distinct()
    }
    if (formulas.isEmpty()) return emptyMap()
    val config = rememberMathConfig(fontSize)
    val measurer = rememberLatexMeasurer(config)
    val density = LocalDensity.current
    return remember(formulas, config, measurer, density, availableWidth) {
        buildMap {
            for (formula in formulas) {
                val content = measurer.inlineContent("\\textstyle " + formula, config) ?: continue
                val placeholder = content.placeholder
                val width = with(density) { placeholder.width.toPx().toDp() }
                val displayedContent = if (availableWidth.value.isFinite() && availableWidth > 0.dp && width > availableWidth) {
                    val viewport = Placeholder(
                        width = with(density) { availableWidth.toSp() },
                        height = placeholder.height,
                        placeholderVerticalAlign = placeholder.placeholderVerticalAlign,
                    )
                    InlineTextContent(viewport) { source ->
                        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                            Box(Modifier.width(width)) {
                                content.children(source)
                            }
                        }
                    }
                } else {
                    content
                }
                put(mathInlineContentId(formula), displayedContent)
            }
        }
    }
}

@Composable
internal fun MathBlockView(node: MathBlockNode, modifier: Modifier = Modifier) {
    val fontSize = LocalTextStyle.current.fontSize
    val config = rememberMathConfig(if (fontSize.isSpecified) (fontSize.value + 2).sp else 18.sp)
    Box(modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Latex(latex = "\\displaystyle " + node.latex, config = config)
    }
}
