@file:Suppress("UnstableApiUsage")

package editor.kotlinparser

import com.intellij.platform.syntax.lexer.performLexing
import com.intellij.platform.syntax.parser.SyntaxTreeBuilderFactory
import com.intellij.platform.syntax.parser.prepareProduction
import org.jetbrains.kotlin.kmp.lexer.KotlinLexer
import org.jetbrains.kotlin.kmp.parser.KotlinParser
import org.jetbrains.kotlin.kmp.tree.LightNode
import org.jetbrains.kotlin.kmp.tree.LightSyntaxTree
import org.jetbrains.kotlin.kmp.tree.buildLanguageSpecificLightTree

/** A syntax error the compiler's parser reports, with offsets into the text and its message. */
class SyntaxError(val from: Int, val to: Int, val message: String) {
    override fun toString(): String = "$from..$to: $message"
}

/**
 * A Kotlin file as the Kotlin compiler's parser sees it: its syntax tree and its syntax errors.
 *
 * The parser is the one the compiler builds FIR from (`compiler/multiplatform-parsing`), so the errors are the
 * compiler's syntax errors, worded as the compiler words them.
 */
class KotlinSyntax private constructor(val text: String, val tree: LightSyntaxTree, val errors: List<SyntaxError>) {
    /** The types of the top-level nodes of the file, as `FUNCTION` or `CLASS`, with their text ranges. */
    val topLevel: List<Triple<String, Int, Int>>
        get() = tree.getChildren(tree.getRoot())
            .filter { tree.isComposite(it) }
            .map { Triple(tree.getType(it).toString(), tree.getStartOffset(it), tree.getEndOffset(it)) }

    /** The tree as indented `TYPE: text` lines, for tests and debugging. */
    fun dump(): String = StringBuilder().also { dump(tree.getRoot(), "", it) }.toString()

    private fun dump(node: LightNode, indent: String, out: StringBuilder) {
        out.append(indent).append(tree.getType(node)).append(": ").append(tree.getText(node).toString().replace("\n", "\\n")).append('\n')
        for (child in tree.getChildren(node)) dump(child, "$indent  ", out)
    }

    companion object {
        /** Parses [text] as a Kotlin file (or script, when [isScript]). */
        fun parse(text: String, isScript: Boolean = false): KotlinSyntax {
            val parser = KotlinParser(isScript = isScript, isLazy = false)
            val builder = SyntaxTreeBuilderFactory.builder(
                text,
                performLexing(text, KotlinLexer(), cancellationProvider = null, logger = null),
                whitespaces = parser.whitespaces,
                comments = parser.comments,
            ).withStartOffset(startOffset = 0)
                .withWhitespaceOrCommentBindingPolicy(parser.whitespaceOrCommentBindingPolicy)
                .build()
            parser.parse(builder)

            // The light tree keeps where errors are but not their messages; the production markers have both.
            val markers = prepareProduction(builder).productionMarkers
            val errors = ArrayList<SyntaxError>()
            for (index in 0 until markers.size) {
                if (markers.isDoneMarker(index)) continue
                val marker = markers.getMarker(index)
                val message = marker.getErrorMessage() ?: continue
                errors += SyntaxError(marker.getStartOffset(), marker.getEndOffset(), message)
            }
            val tree = buildLanguageSpecificLightTree(builder, text, buildLanguageSpecificTreeStructure = { it }, isComment = { false })
            return KotlinSyntax(text, tree, errors.sortedBy { it.from })
        }
    }
}
