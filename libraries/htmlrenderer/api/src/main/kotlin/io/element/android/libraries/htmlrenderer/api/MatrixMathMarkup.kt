/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.htmlrenderer.api

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.nodes.Node as HtmlNode

object MatrixMathMarkup {
    const val ATTRIBUTE = "data-mx-maths"
    private const val DOLLARS = "$$"
    private const val SPACE = """\p{javaWhitespace}\p{Z}"""

    private val delimiters = Regex(
        """(?<![\\\x24])\x24\x24(?!\x24)(?<block>[^\x24\x00]{1,1200}?)(?<!\\)\x24\x24(?!\x24)|""" +
            """(?<![\\\x24])\x24(?![\x24${SPACE}])(?<inline>[^\x24\r\n]{1,1200}?)(?<![\\${SPACE}])\x24(?![\x24\p{Nd}])|""" +
            """(?<!\\)(?<bracketOpen>\\{1,2})\[(?<bracket>[\s\S]{1,1200}?)(?<!\\)\k<bracketOpen>\]|""" +
            """(?<!\\)(?<parenOpen>\\{1,2})\((?<paren>[^\r\n]{1,1200}?)(?<!\\)\k<parenOpen>\)"""
    )
    private val unsupportedCommands = Regex(
        """\\(?:[a-z]*def|newcommand|renewcommand|providecommand|let|futurelet|csname|loop|input|include[a-z]*|href|url|html[a-z]*)\b"""
    )
    private val extensions = listOf(AutolinkExtension.create(), StrikethroughExtension.create(), TablesExtension.create())
    private val markdownParser = Parser.builder().extensions(extensions).includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    private val markdownRenderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build()
    private val excludedTags = setOf("a", "code", "pre", "script", "style", "mx-reply")
    private val plainParagraphTags = setOf("p", "div")
    private val hardBreak = Regex("""(?m)(?<!\\)\\(?=\r?$)""")
    private val escapedEqualsLine = Regex("""(?m)^([ \t]*)\\=([ \t]*)(?=\r?$)""")
    private val escapedRowSpace = Regex("""(?<!\\)(?:\\\\)\\(?=[ \t])""")

    fun safeFormula(latex: String): String? {
        var source = latex.trim()
        if (source.startsWith("\\(") && source.endsWith("\\)")) source = source.drop(2).dropLast(2).trim()
        if (source.isBlank() || source.length > 1200 || unsupportedCommands.containsMatchIn(source)) return null
        if (source.count { it == '{' } > 120) return null
        return source
    }

    fun inlineSource(latex: String): String = "$$latex$"

    private fun displaySource(latex: String): String = "$DOLLARS\n$latex\n$DOLLARS"

    /** Keep the original body when it already carries the math source. Some clients put it only in the HTML. */
    fun copyableText(body: String, document: Document?): String {
        if (document == null) return body
        val mathElements = document.select("span[$ATTRIBUTE], div[$ATTRIBUTE]")
        if (mathElements.isEmpty()) return body
        val htmlFormulas = mathElements.mapNotNull(::taggedFormula)
        if (htmlFormulas.isEmpty() || findInMarkdown(body).includes(htmlFormulas)) return body

        return buildString {
            fun lineBreak() {
                if (isNotEmpty() && last() != '\n') append('\n')
            }

            fun appendNode(node: HtmlNode) {
                when (node) {
                    is TextNode -> append(node.wholeText)
                    is Element -> {
                        val tag = node.normalName()
                        if (tag == "mx-reply") return
                        val latex = if (tag == "span" || tag == "div") safeFormula(node.attr(ATTRIBUTE)) else null
                        if (node.isBlock) lineBreak()
                        when {
                            latex != null -> append(if (tag == "div") displaySource(latex) else inlineSource(latex))
                            tag == "br" -> append('\n')
                            else -> node.childNodes().forEach(::appendNode)
                        }
                        if (node.isBlock) lineBreak()
                    }
                }
            }
            document.body().childNodes().forEach(::appendNode)
        }.trim()
    }

    /** Returns null when the caller can keep using the existing Markdown send path. */
    fun createFormattedBody(markdown: String, html: String?): String? {
        if (!mightContainMath(html.orEmpty()) && !mightContainMath(markdown) && !mightContainTable(markdown)) return null
        if (html != null) {
            val document = Jsoup.parseBodyFragment(html)
            val restored = restoreRichContent(document, markdown)
            if (restored !== document) return restored.body().html()
            document.outputSettings().prettyPrint(false)
            return if (decorateHtml(document)) normalizeHtml(document.body().html()) else null
        }

        val formulas = findInMarkdown(markdown)
        if (formulas.isEmpty()) return renderMarkdownTable(markdown)?.body()?.html()
        return renderMarkdownWithMath(markdown, formulas).body().html()
    }

    /**
     * Older clients can treat a bare equals line inside an untagged display formula as a heading underline.
     * Use the raw message to recover TeX that the sender's Markdown formatter already consumed.
     */
    fun restoreRichContent(document: Document, body: String): Document {
        if (!mightContainMath(body) && !mightContainTable(body)) return document
        val formulas = findInMarkdown(body)
        val tagged = document.select("span[$ATTRIBUTE], div[$ATTRIBUTE]").mapNotNull(::taggedFormula)
        if (!formulas.includes(tagged)) return document
        val restoresMath = formulas.size > tagged.size
        if (!restoresMath && !mightContainTable(body)) return document
        val rebuilt = renderMarkdownWithMath(body, formulas)
        return if (restoresMath || rebuilt.select("table").size > document.select("table").size) rebuilt else document
    }

    private fun mightContainMath(text: String): Boolean = '$' in text || "\\(" in text || "\\[" in text

    private fun mightContainTable(text: String): Boolean = '|' in text && '-' in text && '\n' in text

    private fun renderMarkdownTable(text: String): Document? =
        if (mightContainTable(text)) renderMarkdownWithMath(text, emptyList()).takeIf { it.selectFirst("table") != null } else null

    private fun taggedFormula(element: Element): Pair<String, Boolean>? =
        safeFormula(element.attr(ATTRIBUTE))?.let { it to (element.normalName() == "div") }

    private fun List<Formula>.includes(tagged: List<Pair<String, Boolean>>): Boolean {
        val remaining = map { it.latex to it.display }.toMutableList()
        return tagged.all { remaining.remove(it) }
    }

    private fun renderMarkdownWithMath(markdown: String, formulas: List<Formula>): Document {
        val prefix = generateSequence("mxmathplaceholder") { it + "x" }.first { it !in markdown }
        val prepared = buildString {
            var cursor = 0
            formulas.forEachIndexed { index, formula ->
                append(markdown, cursor, formula.range.first)
                if (formula.display) append("\n\n")
                append(prefix).append(index).append("end")
                if (formula.display) append("\n\n")
                cursor = formula.range.last + 1
            }
            append(markdown, cursor, markdown.length)
        }
        val rendered = markdownRenderer.render(markdownParser.parse(prepared))
        val withFormulas = Regex(prefix + """(\d+)end""").replace(rendered) { token ->
            val formula = formulas[token.groupValues[1].toInt()]
            formula.toElement().outerHtml()
        }
        return normalizedDocument(withFormulas)
    }

    private fun normalizeHtml(html: String): String = normalizedDocument(html).body().html()

    private fun normalizedDocument(html: String): Document {
        // Reparse to place display formulas outside their enclosing paragraph.
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        document.select("p:empty").remove()
        return document
    }

    fun decorateHtml(document: Document): Boolean {
        if (!mightContainMath(document.body().wholeText())) return false
        var changed = false
        fun visit(element: Element) {
            if (element.hasAttr(ATTRIBUTE) || element.normalName() in excludedTags) return
            if (replaceDisplayMathAcrossParagraphs(element)) changed = true
            if (replaceDisplayMathAcrossBreaks(element)) changed = true
            element.childNodes().toList().forEach { child ->
                when (child) {
                    is TextNode -> {
                        val text = child.wholeText
                        val matches = find(text)
                        if (matches.isNotEmpty()) {
                            var cursor = 0
                            for (formula in matches) {
                                if (formula.range.first > cursor) child.before(TextNode(text.substring(cursor, formula.range.first)))
                                child.before(formula.toElement())
                                cursor = formula.range.last + 1
                            }
                            if (cursor < text.length) child.before(TextNode(text.substring(cursor)))
                            child.remove()
                            changed = true
                        }
                    }
                    is Element -> visit(child)
                }
            }
        }
        visit(document.body())
        return changed
    }

    private fun replaceDisplayMathAcrossParagraphs(element: Element): Boolean {
        if (element.childNodeSize() < 2) return false
        val paragraphs = mutableListOf<Element>()
        var changed = false
        fun flush() {
            if (paragraphs.size > 1) {
                val text = paragraphs.joinToString("\n") { it.wholeText() }
                val matches = find(text).filter { it.display && '\n' in text.substring(it.range) }
                if (matches.isNotEmpty()) {
                    replaceRun(paragraphs, text, matches)
                    changed = true
                }
            }
            paragraphs.clear()
        }
        for (child in element.childNodes().toList()) {
            when {
                child is Element && child.normalName() in plainParagraphTags && child.attributes().isEmpty &&
                    child.childNodes().all { it is TextNode || it is Element && it.normalName() == "br" } -> paragraphs.add(child)
                child is TextNode && child.isBlank -> Unit
                else -> flush()
            }
        }
        flush()
        return changed
    }

    private fun replaceDisplayMathAcrossBreaks(element: Element): Boolean {
        val children = element.childNodes().toList()
        if (children.none { it is Element && it.normalName() == "br" }) return false
        val run = mutableListOf<HtmlNode>()
        var changed = false
        fun flush() {
            if (run.isEmpty()) return
            val text = run.joinToString("") { if (it is TextNode) it.wholeText else "\n" }
            val matches = find(text).filter { it.display }
            if (matches.isNotEmpty()) {
                replaceRun(run, text, matches)
                changed = true
            }
            run.clear()
        }
        children.forEach { child ->
            if (child is TextNode || child is Element && child.normalName() == "br") run.add(child) else flush()
        }
        flush()
        return changed
    }

    private fun replaceRun(nodes: List<HtmlNode>, text: String, matches: List<Formula>) {
        val replacement = Element("div")
        appendFormulas(replacement, text, matches)
        replacement.childNodes().toList().forEach { nodes.first().before(it) }
        nodes.forEach { it.remove() }
    }

    fun plainTextWithRichContent(text: String): Document? {
        val matches = findInMarkdown(text)
        if (matches.isEmpty()) return renderMarkdownTable(text)
        if (mightContainTable(text)) {
            val formatted = renderMarkdownWithMath(text, matches)
            if (formatted.selectFirst("table") != null) return formatted
        }
        val doc = Document.createShell("")
        appendFormulas(doc.body(), text, matches)
        return doc
    }

    private fun appendFormulas(container: Element, text: String, matches: List<Formula>) {
        fun appendPlain(value: String) {
            value.lineSequence().forEachIndexed { index, line ->
                if (index > 0) container.appendElement("br")
                if (line.isNotEmpty()) container.appendChild(TextNode(line))
            }
        }
        var cursor = 0
        for (formula in matches) {
            val before = text.substring(cursor, formula.range.first)
            appendPlain(if (formula.display) before.removeSuffix("\n").removeSuffix("\r") else before)
            container.appendChild(formula.toElement())
            cursor = formula.range.last + 1
            if (formula.display) {
                cursor += when {
                    text.startsWith("\r\n", cursor) -> 2
                    text.startsWith("\n", cursor) -> 1
                    else -> 0
                }
            }
        }
        appendPlain(text.substring(cursor))
    }

    private fun findInMarkdown(text: String): List<Formula> {
        val candidates = find(text)
        if (candidates.isEmpty()) return candidates
        val excluded = buildList {
            fun visit(node: Node) {
                if (node is Code || node is FencedCodeBlock || node is IndentedCodeBlock ||
                    node is HtmlInline || node is HtmlBlock || node is Link || node is Image
                ) {
                    node.sourceSpans.forEach { add(it.inputIndex until it.inputIndex + it.length) }
                    return
                }
                var child = node.firstChild
                while (child != null) {
                    visit(child)
                    child = child.next
                }
            }
            visit(markdownParser.parse(text))
        }
        return candidates.filterNot { formula ->
            excluded.any { it.first <= formula.range.last && formula.range.first <= it.last }
        }
    }

    private fun find(text: String): List<Formula> {
        if (!mightContainMath(text)) return emptyList()
        return delimiters.findAll(text).mapNotNull { match ->
            val block = match.groups["block"]?.value ?: match.groups["bracket"]?.value
            val raw = block ?: match.groups["inline"]?.value ?: match.groups["paren"]?.value.orEmpty()
            val slashCount = (match.groups["bracketOpen"] ?: match.groups["parenOpen"])?.value?.length
            val latex = if (slashCount == 2) {
                raw.replace("\\_{", "_{").replace("\\\\|", "\\|")
            } else if (block != null) {
                displayLatex(raw)
            } else {
                raw
            }
            safeFormula(latex)?.let { Formula(match.range, it, block != null) }
        }.toList()
    }

    private fun displayLatex(source: String): String {
        // Some Markdown exports explicitly mark every line break, including the one after the opening fence.
        if (!source.startsWith("\\\n") && !source.startsWith("\\\r\n")) return source
        return source.replace(hardBreak, "")
            .replace("\\_{", "_{")
            .replace("\\\\,", "\\,")
            .replace(escapedRowSpace) { "\\\\" }
            .replace(escapedEqualsLine) { it.groupValues[1] + "=" + it.groupValues[2] }
    }

    private data class Formula(val range: IntRange, val latex: String, val display: Boolean) {
        fun toElement(): Element = Element(if (display) "div" else "span")
            .attr(ATTRIBUTE, latex)
            .appendElement("code")
            .text(latex)
            .parent()!!
    }
}
