/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.htmlrenderer.impl

import android.net.Uri
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.htmlrenderer.api.CodeBlockNode
import io.element.android.libraries.htmlrenderer.api.HeaderNode
import io.element.android.libraries.htmlrenderer.api.HtmlMessageParser
import io.element.android.libraries.htmlrenderer.api.INLINE_MATH_ANNOTATION
import io.element.android.libraries.htmlrenderer.api.ListNode
import io.element.android.libraries.htmlrenderer.api.MathBlockNode
import io.element.android.libraries.htmlrenderer.api.MatrixMathMarkup
import io.element.android.libraries.htmlrenderer.api.MentionNodeContent
import io.element.android.libraries.htmlrenderer.api.ParagraphNode
import io.element.android.libraries.htmlrenderer.api.QuoteNode
import io.element.android.libraries.htmlrenderer.api.TableNode
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomAlias
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.test.permalink.FakePermalinkBuilder
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.jsoup.Jsoup
import org.junit.Test

class DefaultHtmlMessageParserTest : RobolectricTest() {
    @Test
    fun `raw body restores an integral and matrices whose equals line was parsed as a heading`() {
        val fence = "${'$'}${'$'}"
        val integral = """\int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi}"""
        val matrix = """
            \begin{pmatrix} a & b \\ c & d \end{pmatrix}
            \begin{pmatrix} x \\ y \end{pmatrix}
            =
            \begin{pmatrix} ax + by \\ cx + dy \end{pmatrix}
        """.trimIndent()
        val raw = "Integral\n\n$fence\n$integral\n$fence\n\nMatrix\n\n$fence\n$matrix\n$fence"
        val legacy = Jsoup.parseBodyFragment(
            "<p>Integral</p><p>$fence<br>\\int_{-\\infty}^{\\infty} e^{-x^2},dx = \\sqrt{\\pi}<br>$fence</p>" +
                "<p>Matrix</p><h1>$fence<br>\\begin{pmatrix} a &amp; b \\ c &amp; d \\end{pmatrix}</h1>" +
                "<p>\\begin{pmatrix} ax + by \\ cx + dy \\end{pmatrix}<br>$fence</p>",
        )
        val restored = MatrixMathMarkup.restoreRichContent(legacy, raw)
        val nodes = parse(restored.body().html()).children
        assertThat(nodes.filterIsInstance<HeaderNode>()).isEmpty()
        assertThat(nodes.filterIsInstance<MathBlockNode>().map { it.latex }).containsExactly(integral, matrix).inOrder()
    }

    @Test
    fun `an exported hard-break formula preserves TeX row breaks and a bare equals line`() {
        val fence = "${'$'}${'$'}"
        val raw = listOf(
            fence + '\\',
            """\begin{pmatrix} a & b \\\ c & d \end{pmatrix}""" + '\\',
            """\=""" + '\\',
            """\begin{pmatrix} x \\\ y \end{pmatrix}""" + '\\',
            fence,
        ).joinToString("\n")
        val node = parse(checkNotNull(MatrixMathMarkup.createFormattedBody(raw, null))).children.single() as MathBlockNode
        assertThat(node.latex).isEqualTo(
            """\begin{pmatrix} a & b \\ c & d \end{pmatrix}""" + "\n=\n" + """\begin{pmatrix} x \\ y \end{pmatrix}""",
        )
    }

    @Test
    fun `pasted math delimiters and norm symbols survive Markdown parsing inside a table`() {
        val notes = """
            ### Taylor expansion

            若 \(f\in C^2\)，有

            \\[
            f(a+h) = f(a) + \nabla f(a)\cdot h + \tfrac12 h^{\mathsf T}H_f(a)h
            \\]

            | 余项 | 含义 | 用途 |
            | --- | --- | --- |
            | **Peano** | \\(o(\|h\|^k)\\) | 判断符号 |
            | Lagrange | \(a+\theta h\) | 估计误差 |
        """.trimIndent()
        val html = checkNotNull(MatrixMathMarkup.createFormattedBody(notes, null))
        val nodes = parse(html).children
        assertThat(nodes.filterIsInstance<MathBlockNode>().single().latex).contains("\\nabla f(a)")
        val table = nodes.filterIsInstance<TableNode>().single()
        assertThat(table.rows.map { it.cells.size }).containsExactly(3, 3, 3)
        assertThat(table.rows[0].isHeader).isTrue()
        val norm = table.rows[1].cells[1].text
        assertThat(norm.getStringAnnotations(INLINE_MATH_ANNOTATION, 0, norm.length).single().item)
            .isEqualTo("""o(\|h\|^k)""")
    }

    @Test
    fun `a Markdown table without formulas is sent as an HTML table`() {
        val raw = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val html = checkNotNull(MatrixMathMarkup.createFormattedBody(raw, null))
        val table = parse(html).children.single() as TableNode
        assertThat(table.rows[1].cells.map { it.text.text }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `the composer serializes inline and display formulas as Matrix HTML`() {
        val markdown = "Ratio: ${'$'}\\frac{a}{b}${'$'}\n\n${'$'}${'$'}\nx < y\n${'$'}${'$'}"
        val html = checkNotNull(MatrixMathMarkup.createFormattedBody(markdown, null))
        val parsed = Jsoup.parseBodyFragment(html)
        assertThat(parsed.select("span[data-mx-maths]").single().attr(MatrixMathMarkup.ATTRIBUTE)).isEqualTo("\\frac{a}{b}")
        assertThat(parsed.select("div[data-mx-maths]").single().attr(MatrixMathMarkup.ATTRIBUTE)).isEqualTo("x < y")
        assertThat(parse(html).children.last()).isInstanceOf(MathBlockNode::class.java)
    }

    @Test
    fun `display delimiters work with rich text line breaks`() {
        val html = "<p>${'$'}${'$'}<br>\\frac{a}{b}<br>${'$'}${'$'}</p>"
        val formatted = checkNotNull(MatrixMathMarkup.createFormattedBody("", html))
        val block = parse(formatted).children.single() as MathBlockNode
        assertThat(block.latex).isEqualTo("\\frac{a}{b}")
    }

    @Test
    fun `display math in the middle of a Chinese sentence becomes a block`() {
        val markdown = "块公式：输入 ${'$'}${'$'}\\frac{a}{b}${'$'}${'$'}，尾部的 ${'$'}${'$'} 保留。"
        val formatted = checkNotNull(MatrixMathMarkup.createFormattedBody(markdown, null))
        val children = parse(formatted).children
        assertThat(children).hasSize(3)
        assertThat((children[0] as ParagraphNode).text.text).isEqualTo("块公式：输入")
        assertThat((children[1] as MathBlockNode).latex).isEqualTo("\\frac{a}{b}")
        assertThat((children[2] as ParagraphNode).text.text).isEqualTo("，尾部的 ${'$'}${'$'} 保留。")
    }

    @Test
    fun `rich text paragraphs can contain a single multiline formula`() {
        val html = "<p>Before</p><p>${'$'}${'$'}</p><p>\\frac{a}{b}</p><p>${'$'}${'$'}</p><p>After</p>"
        val formatted = checkNotNull(MatrixMathMarkup.createFormattedBody("", html))
        val children = parse(formatted).children
        assertThat(children).hasSize(3)
        assertThat((children[1] as MathBlockNode).latex).isEqualTo("\\frac{a}{b}")
    }

    @Test
    fun `inline selection keeps dollar markers and normalizes a redundant LaTex wrapper`() {
        val paragraph = parse("<span data-mx-maths='\\(\\frac{a}{b}\\)'><code>fraction</code></span>").children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("${'$'}\\frac{a}{b}${'$'}")
        assertThat(paragraph.text.getStringAnnotations(INLINE_MATH_ANNOTATION, 0, paragraph.text.length).single().item)
            .isEqualTo("\\frac{a}{b}")
    }

    @Test
    fun `copy restores the source when another client omits delimiters from its fallback body`() {
        val html = "<mx-reply>Old message</mx-reply><p>比例是<span data-mx-maths='\\frac{a}{b}'>fraction</span>。</p>" +
            "<div data-mx-maths='x &lt; y'>inequality</div>"
        val text = MatrixMathMarkup.copyableText("比例是 fraction。\ninequality", Jsoup.parseBodyFragment(html))
        assertThat(text).isEqualTo("比例是${'$'}\\frac{a}{b}${'$'}。\n${'$'}${'$'}\nx < y\n${'$'}${'$'}")
        assertThat(MatrixMathMarkup.createFormattedBody(text, null)).isNotNull()
    }

    @Test
    fun `currency ranges and code keep their literal dollars`() {
        val markdown = "Costs ${'$'}5–${'$'}10. Code: `${'$'}x${'$'}`."
        assertThat(MatrixMathMarkup.createFormattedBody(markdown, null)).isNull()
    }

    @Test
    fun `inline fences reject whitespace inside and digits after the closing dollar only`() {
        val dollar = '$'
        val literals = listOf("${dollar} (x)${dollar}", "${dollar}x ${dollar}", "${dollar}x${dollar}2", "＄x＄", "\\${dollar}x\\${dollar}")
        for (text in literals) {
            assertThat(MatrixMathMarkup.createFormattedBody(text, null)).isNull()
        }
        for (text in listOf("比例是${dollar}(x)${dollar}。", "2${dollar}x${dollar}", "${dollar}2x${dollar}")) {
            assertThat(MatrixMathMarkup.createFormattedBody(text, null)).isNotNull()
        }
    }

    @Test
    fun `plain text becomes a single paragraph`() {
        val document = parse("Hello world")
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Hello world")
        assertThat(paragraph.inlineContent).isEmpty()
    }

    @Test
    fun `inline formatting is encoded as span styles`() {
        val document = parse("Hello <strong>bold</strong> and <em>italic</em>")
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Hello bold and italic")

        val boldStart = paragraph.text.text.indexOf("bold")
        val bold = paragraph.text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertThat(bold.start).isEqualTo(boldStart)
        assertThat(bold.end).isEqualTo(boldStart + "bold".length)

        val italicStart = paragraph.text.text.indexOf("italic")
        val italic = paragraph.text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertThat(italic.start).isEqualTo(italicStart)
        assertThat(italic.end).isEqualTo(italicStart + "italic".length)
    }

    @Test
    fun `unordered list produces a bulleted ListNode`() {
        val document = parse("<ul><li>First</li><li>Second</li></ul>")
        val list = document.children.single() as ListNode
        assertThat(list.ordered).isFalse()
        assertThat(list.startIndex).isEqualTo(1)
        assertThat(list.items).hasSize(2)
        val firstItem = list.items[0].children.single() as ParagraphNode
        assertThat(firstItem.text.text).isEqualTo("First")
    }

    @Test
    fun `ordered list honors the start attribute`() {
        val document = parse("""<ol start="5"><li>Fifth</li></ol>""")
        val list = document.children.single() as ListNode
        assertThat(list.ordered).isTrue()
        assertThat(list.startIndex).isEqualTo(5)
    }

    @Test
    fun `a list nested inside a list item is preserved`() {
        val document = parse("<ul><li>Parent<ul><li>Child</li></ul></li></ul>")
        val outerList = document.children.single() as ListNode
        val outerItem = outerList.items.single()
        // The item holds a paragraph ("Parent") followed by the nested list.
        val paragraph = outerItem.children[0] as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Parent")
        val nestedList = outerItem.children[1] as ListNode
        val nestedItem = nestedList.items.single().children.single() as ParagraphNode
        assertThat(nestedItem.text.text).isEqualTo("Child")
    }

    @Test
    fun `blockquote wraps its block children`() {
        val document = parse("<blockquote><p>Quoted</p></blockquote>")
        val quote = document.children.single() as QuoteNode
        val paragraph = quote.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Quoted")
    }

    @Test
    fun `blockquotes can nest`() {
        val document = parse("<blockquote><blockquote><p>Deep</p></blockquote></blockquote>")
        val outer = document.children.single() as QuoteNode
        val inner = outer.children.single() as QuoteNode
        val paragraph = inner.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Deep")
    }

    @Test
    fun `inline code is monospaced and annotated`() {
        val document = parse("Run <code>git status</code> now")
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Run git status now")

        val start = paragraph.text.text.indexOf("git status")
        val end = start + "git status".length
        val monospace = paragraph.text.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertThat(monospace.start).isEqualTo(start)
        assertThat(monospace.end).isEqualTo(end)

        val codeAnnotations = paragraph.text.getStringAnnotations(HtmlMessageParser.INLINE_CODE_ANNOTATION_TAG, start, end)
        assertThat(codeAnnotations).hasSize(1)
    }

    @Test
    fun `pre becomes a code block with raw text, and its inner code is not treated as inline code`() {
        val document = parse("<pre><code>val x = 1\nval y = 2</code></pre>")
        val codeBlock = document.children.single() as CodeBlockNode
        assertThat(codeBlock.code).isEqualTo("val x = 1\nval y = 2")
    }

    @Test
    fun `a regular link is styled and annotated with its url`() {
        val permalinkParser = FakePermalinkParser { PermalinkData.FallbackLink(Uri.parse(it)) }
        val document = parse("""Visit <a href="https://element.io">the site</a>""", permalinkParser)
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.inlineContent).isEmpty()

        val linkStart = paragraph.text.text.indexOf("the site")
        val annotations = paragraph.text.getStringAnnotations(
            tag = HtmlMessageParser.LINK_ANNOTATION_TAG,
            start = linkStart,
            end = linkStart + "the site".length,
        )
        assertThat(annotations.single().item).isEqualTo("https://element.io")
    }

    @Test
    fun `a user link becomes a mention pill`() {
        val userId = UserId("@alice:example.org")
        val permalinkParser = FakePermalinkParser { PermalinkData.UserLink(userId) }
        val document = parse("""Hi <a href="https://matrix.to/#/@alice:example.org">Alice</a>""", permalinkParser)
        val paragraph = document.children.single() as ParagraphNode

        val mention = paragraph.inlineContent.values.single()
        assertThat(mention).isEqualTo(MentionNodeContent.User(displayText = "Alice", userId = userId, permalinkUrl = "https://matrix.to/#/@alice:example.org"))
        // The placeholder id in the text matches the map key.
        val placeholderId = paragraph.inlineContent.keys.single()
        val annotations = paragraph.text.getStringAnnotations(0, paragraph.text.length)
        assertThat(annotations.any { it.item == placeholderId }).isTrue()
    }

    @Test
    fun `a room link becomes a room mention pill`() {
        val roomIdOrAlias = RoomAlias("#room:example.org").toRoomIdOrAlias()
        val permalinkParser = FakePermalinkParser {
            PermalinkData.RoomLink(roomIdOrAlias = roomIdOrAlias)
        }
        val document = parse("""See <a href="https://matrix.to/#/#room:example.org">the room</a>""", permalinkParser)
        val paragraph = document.children.single() as ParagraphNode
        val mention = paragraph.inlineContent.values.single()
        assertThat(mention).isEqualTo(
            MentionNodeContent.Room(displayText = "the room", roomIdOrAlias = roomIdOrAlias, permalinkUrl = "https://matrix.to/#/#room:example.org")
        )
    }

    @Test
    fun `a link to an event is a regular link, not a mention`() {
        val permalinkParser = FakePermalinkParser {
            PermalinkData.RoomLink(
                roomIdOrAlias = RoomId("!room:example.org").toRoomIdOrAlias(),
                eventId = EventId("\$Rqnc-F-dvnEYJTyHq_iKxU2bZ1CI92-kuZq3a5lr5Zg"),
            )
        }
        val document = parse("""Look <a href="https://matrix.to/#/!room:example.org/event">here</a>""", permalinkParser)
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.inlineContent).isEmpty()
    }

    @Test
    fun `raw mentions in the text become mention pills next to the existing ones`() {
        val alice = UserId("@alice:example.org")
        val permalinkParser = FakePermalinkParser { url ->
            if (url == "https://matrix.to/#/@alice:example.org") PermalinkData.UserLink(alice) else PermalinkData.FallbackLink(Uri.parse(url))
        }
        val document = parse(
            """<p><a href="https://matrix.to/#/@alice:example.org">Alice</a>, @bob:example.org and @room</p><h2>Join #room:example.org</h2>""",
            permalinkParser,
        )
        val paragraph = document.children[0] as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Alice, @bob:example.org and @room")
        assertThat(paragraph.inlineContent.values).containsExactly(
            MentionNodeContent.User(displayText = "Alice", userId = alice, permalinkUrl = "https://matrix.to/#/@alice:example.org"),
            MentionNodeContent.User(
                displayText = "@bob:example.org",
                userId = UserId("@bob:example.org"),
                permalinkUrl = "https://matrix.to/#/@bob:example.org"
            ),
            MentionNodeContent.Everyone(displayText = "@room"),
        )
        val header = document.children[1] as HeaderNode
        assertThat(header.inlineContent.values.single()).isEqualTo(
            MentionNodeContent.Room(
                displayText = "#room:example.org",
                roomIdOrAlias = RoomAlias("#room:example.org").toRoomIdOrAlias(),
                permalinkUrl = "https://matrix.to/#/#room:example.org"
            )
        )
    }

    @Test
    fun `mx-reply content is stripped`() {
        val document = parse("<mx-reply>In reply to something</mx-reply>Actual message")
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Actual message")
    }

    @Test
    fun `headers become HeaderNodes with their level and inline content`() {
        val document = parse("<h1>Title</h1><h3>Subtitle with <strong>bold</strong></h3><h6>Small</h6>")
        assertThat(document.children).hasSize(3)

        val h1 = document.children[0] as HeaderNode
        assertThat(h1.level).isEqualTo(1)
        assertThat(h1.text.text).isEqualTo("Title")

        val h3 = document.children[1] as HeaderNode
        assertThat(h3.level).isEqualTo(3)
        assertThat(h3.text.text).isEqualTo("Subtitle with bold")

        val h6 = document.children[2] as HeaderNode
        assertThat(h6.level).isEqualTo(6)
        assertThat(h6.text.text).isEqualTo("Small")
    }

    @Test
    fun `blank input produces an empty document`() {
        val document = parse("   ")
        assertThat(document.children).isEmpty()
    }

    @Test
    fun `formatPlainText returns null for blank text`() {
        val parser = createParser()
        assertThat(parser.formatPlainText("")).isNull()
        assertThat(parser.formatPlainText("  \n ")).isNull()
    }

    @Test
    fun `formatPlainText returns a single paragraph with the raw text`() {
        val document = createParser().formatPlainText("Hello\n<b>world</b>")!!
        val paragraph = document.children.single() as ParagraphNode
        // The text is not parsed as HTML
        assertThat(paragraph.text.text).isEqualTo("Hello\n<b>world</b>")
        assertThat(paragraph.inlineContent).isEmpty()
    }

    @Test
    fun `formatPlainText pillifies the mentions in the text`() {
        val document = createParser().formatPlainText("Hi @bob:example.org, join #room:example.org")!!
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Hi @bob:example.org, join #room:example.org")
        assertThat(paragraph.inlineContent.values).containsExactly(
            MentionNodeContent.User(
                displayText = "@bob:example.org",
                userId = UserId("@bob:example.org"),
                permalinkUrl = "https://matrix.to/#/@bob:example.org"
            ),
            MentionNodeContent.Room(
                displayText = "#room:example.org",
                roomIdOrAlias = RoomAlias("#room:example.org").toRoomIdOrAlias(),
                permalinkUrl = "https://matrix.to/#/#room:example.org"
            ),
        )
    }

    private fun createParser(
        permalinkParser: FakePermalinkParser = FakePermalinkParser { PermalinkData.FallbackLink(Uri.parse(it)) },
        permalinkBuilder: FakePermalinkBuilder = FakePermalinkBuilder(
            permalinkForUserLambda = { Result.success("https://matrix.to/#/${it.value}") },
            permalinkForRoomAliasLambda = { Result.success("https://matrix.to/#/${it.value}") },
        ),
    ) = DefaultHtmlMessageParser(permalinkParser, permalinkBuilder)

    private fun parse(
        html: String,
        permalinkParser: FakePermalinkParser = FakePermalinkParser(),
        permalinkBuilder: FakePermalinkBuilder = FakePermalinkBuilder(
            permalinkForUserLambda = { Result.success("https://matrix.to/#/${it.value}") },
            permalinkForRoomAliasLambda = { Result.success("https://matrix.to/#/${it.value}") },
        ),
    ) = DefaultHtmlMessageParser(permalinkParser, permalinkBuilder).parse(Jsoup.parse(html))
}
