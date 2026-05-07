/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.ktfmt.format

import com.facebook.ktfmt.debughelpers.printOps
import com.facebook.ktfmt.format.RedundantElementManager.addRedundantElements
import com.facebook.ktfmt.format.RedundantElementManager.dropRedundantElements
import com.facebook.ktfmt.format.WhitespaceTombstones.indexOfWhitespaceTombstone
import com.facebook.ktfmt.kdoc.Escaping
import com.facebook.ktfmt.kdoc.KDocCommentsHelper
import com.google.common.collect.ImmutableList
import com.google.common.collect.Range
import com.google.googlejavaformat.Doc
import com.google.googlejavaformat.DocBuilder
import com.google.googlejavaformat.Newlines
import com.google.googlejavaformat.OpsBuilder
import com.google.googlejavaformat.java.FormatterException
import com.google.googlejavaformat.java.JavaOutput
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtilRt.convertLineSeparators
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

object Formatter {

  @JvmField
  val META_FORMAT =
      FormattingOptions(
          blockIndent = 2,
          continuationIndent = 4,
          trailingCommaManagementStrategy = TrailingCommaManagementStrategy.ONLY_ADD,
      )

  @JvmField
  val GOOGLE_FORMAT =
      FormattingOptions(
          blockIndent = 2,
          continuationIndent = 2,
      )

  /** A format that attempts to reflect https://kotlinlang.org/docs/coding-conventions.html. */
  @JvmField
  val KOTLINLANG_FORMAT =
      FormattingOptions(
          blockIndent = 4,
          continuationIndent = 4,
      )

  private val MINIMUM_KOTLIN_VERSION = KotlinVersion(1, 4)

  /**
   * format formats the Kotlin code given in 'code' and returns it as a string. This method is
   * accessed through Reflection.
   */
  @JvmStatic
  @Throws(FormatterException::class, ParseError::class)
  fun format(code: String): String = format(META_FORMAT, code)

  /**
   * format formats the Kotlin code given in 'code' with 'removeUnusedImports' and returns it as a
   * string. This method is accessed through Reflection.
   */
  @JvmStatic
  @Throws(FormatterException::class, ParseError::class)
  fun format(code: String, removeUnusedImports: Boolean): String =
      format(META_FORMAT.copy(removeUnusedImports = removeUnusedImports), code)

  /**
   * format formats the Kotlin code given in 'code' with the 'maxWidth' and returns it as a string.
   */
  @JvmStatic
  @Throws(FormatterException::class, ParseError::class)
  fun format(options: FormattingOptions, code: String): String {
    return format(options, code, emptyList())
  }

  /**
   * format formats the Kotlin code given in 'code' and returns it as a string. When line ranges are
   * provided, only formatter replacements intersecting those 1-based inclusive ranges are applied.
   */
  @JvmStatic
  @Throws(FormatterException::class, ParseError::class)
  fun format(options: FormattingOptions, code: String, lineRanges: List<LineRange>): String {
    val (shebang, kotlinCode) =
        if (code.startsWith("#!")) {
          code.split("\n".toRegex(), limit = 2)
        } else {
          listOf("", code)
        }
    checkEscapeSequences(kotlinCode)

    val normalizedCode = convertLineSeparators(kotlinCode)
    val formattedCode =
        if (lineRanges.isEmpty()) {
          normalizedCode
              .let { sortedAndDistinctImports(it) }
              .let { dropRedundantElements(it, options) }
              .let { addRedundantElements(it, options) }
              .let { prettyPrint(it, options, lineSeparator = "\n") }
              .let { addRedundantElements(it, options) }
              .let { MultilineStringFormatter(options.continuationIndent).format(it) }
        } else {
          formatLineRanges(normalizedCode, options, lineRanges)
        }

    return formattedCode
        .let { convertLineSeparators(it, checkNotNull(Newlines.guessLineSeparator(kotlinCode))) }
        .let { if (shebang.isEmpty()) it else shebang + "\n" + it }
  }

  /** prettyPrint reflows 'code' using google-java-format's engine. */
  private fun prettyPrint(
      code: String,
      options: FormattingOptions,
      lineSeparator: String,
      characterRanges: Collection<Range<Int>> = ImmutableList.of(Range.closedOpen(0, code.length)),
  ): String {
    val file = Parser.parse(code)
    val kotlinInput = KotlinInput(code, file)
    val javaOutput =
        JavaOutput(lineSeparator, kotlinInput, KDocCommentsHelper(lineSeparator, options.maxWidth))
    val tokenRangeSet = kotlinInput.characterRangesToTokenRanges(characterRanges)
    for (tokenRange in tokenRangeSet.asRanges()) {
      val startToken = kotlinInput.getToken(tokenRange.lowerEndpoint()) ?: continue
      val endToken = kotlinInput.getToken(tokenRange.upperEndpoint() - 1) ?: continue
      javaOutput.markForPartialFormat(startToken, endToken)
    }
    val builder = OpsBuilder(kotlinInput, javaOutput)
    file.accept(createAstVisitor(options, builder))
    builder.sync(kotlinInput.text.length)
    builder.drain()
    val ops = builder.build()
    if (options.debuggingPrintOpsAfterFormatting) {
      printOps(ops)
    }
    val doc = DocBuilder().withOps(ops).build()
    doc.computeBreaks(javaOutput.commentsHelper, options.maxWidth, Doc.State(+0, 0))
    doc.write(javaOutput)
    javaOutput.flush()

    val replacements =
        javaOutput.getFormatReplacements(tokenRangeSet).filter { replacement ->
          characterRanges.any { range -> rangesIntersect(replacement.replaceRange, range) }
        }
    return WhitespaceTombstones.replaceTombstoneWithTrailingWhitespace(
        JavaOutput.applyReplacements(code, replacements)
    )
  }

  private fun lineRangesToCharacterRanges(
      code: String,
      lineRanges: List<LineRange>,
  ): List<Range<Int>> {
    val lineStartOffsets = mutableListOf(0)
    for (index in code.indices) {
      if (code[index] == '\n' && index + 1 < code.length) {
        lineStartOffsets.add(index + 1)
      }
    }

    return lineRanges.mapNotNull { lineRange ->
      val startOffset = lineStartOffsets.getOrNull(lineRange.start - 1) ?: return@mapNotNull null
      val endOffset =
          lineStartOffsets.getOrNull(lineRange.end)?.let { nextLineStart -> nextLineStart - 1 }
              ?: code.length
      Range.closedOpen(startOffset, endOffset)
    }
  }

  private fun formatLineRanges(
      code: String,
      options: FormattingOptions,
      lineRanges: List<LineRange>,
  ): String {
    val file = Parser.parse(code)
    val functionRanges =
        file
            .collectDescendantsOfType<KtNamedFunction>()
            .filter { function ->
              val functionLineRange =
                  LineRange(
                      StringUtil.offsetToLineColumn(code, function.startOffset).line + 1,
                      StringUtil.offsetToLineColumn(code, function.endOffset).line + 1,
                  )
              lineRanges.any { lineRange -> lineRangesIntersect(functionLineRange, lineRange) }
            }
            .map { Range.closedOpen(it.startOffset, it.endOffset) }
            .let { ranges ->
              ranges.filter { range ->
                ranges.none { other -> other != range && rangeEncloses(other, range) }
              }
            }

    if (functionRanges.isEmpty()) {
      return prettyPrint(
          code,
          options,
          lineSeparator = "\n",
          characterRanges = lineRangesToCharacterRanges(code, lineRanges),
      )
    }

    val formattedCode = StringBuilder(code)
    for (range in functionRanges.sortedByDescending { it.lowerEndpoint() }) {
      val original = code.substring(range.lowerEndpoint(), range.upperEndpoint())
      val formatted =
          prettyPrint(original, options, lineSeparator = "\n").trimTrailingNewlineLike(original)
      formattedCode.replace(range.lowerEndpoint(), range.upperEndpoint(), formatted)
    }
    return formattedCode.toString()
  }

  private fun String.trimTrailingNewlineLike(original: String): String {
    return if (original.endsWith("\n") || !endsWith("\n")) this else dropLast(1)
  }

  private fun lineRangesIntersect(first: LineRange, second: LineRange): Boolean =
      first.start <= second.end && second.start <= first.end

  private fun rangeEncloses(first: Range<Int>, second: Range<Int>): Boolean =
      first.lowerEndpoint() <= second.lowerEndpoint() &&
          first.upperEndpoint() >= second.upperEndpoint()

  private fun rangesIntersect(first: Range<Int>, second: Range<Int>): Boolean {
    if (first.isEmpty) {
      return second.contains(first.lowerEndpoint())
    }
    if (second.isEmpty) {
      return first.contains(second.lowerEndpoint())
    }
    return first.isConnected(second) && !first.intersection(second).isEmpty
  }

  private fun createAstVisitor(options: FormattingOptions, builder: OpsBuilder): PsiElementVisitor {
    if (KotlinVersion.CURRENT < MINIMUM_KOTLIN_VERSION) {
      throw RuntimeException("Unsupported runtime Kotlin version: " + KotlinVersion.CURRENT)
    }
    return KotlinInputAstVisitor(options, builder)
  }

  private fun checkEscapeSequences(code: String) {
    var index = code.indexOfWhitespaceTombstone()
    if (index == -1) {
      index = Escaping.indexOfCommentEscapeSequences(code)
    }
    if (index != -1) {
      throw ParseError(
          "ktfmt does not support code which contains one of {\\u0003, \\u0004, \\u0005} character" +
              "; escape it",
          StringUtil.offsetToLineColumn(code, index),
      )
    }
  }

  private fun sortedAndDistinctImports(code: String): String {
    val file = Parser.parse(code)

    val importList = file.importList ?: return code
    if (importList.imports.isEmpty()) {
      return code
    }

    val commentList = mutableListOf<PsiElement>()
    // Find non-import elements; comments are moved, in order, to the top of the import list. Other
    // non-import elements throw a ParseError.
    var element = importList.firstChild
    while (element != null) {
      if (element is PsiComment) {
        commentList.add(element)
      } else if (element !is KtImportDirective && element !is PsiWhiteSpace) {
        throw ParseError(
            "Imports not contiguous: " + element.text,
            StringUtil.offsetToLineColumn(code, element.startOffset),
        )
      }
      element = element.nextSibling
    }
    fun canonicalText(importDirective: KtImportDirective) =
        importDirective.importedFqName?.asString() +
            " " +
            importDirective.alias?.text?.replace("`", "") +
            " " +
            if (importDirective.isAllUnder) "*" else ""

    val sortedImports = importList.imports.sortedBy(::canonicalText).distinctBy(::canonicalText)
    val importsWithComments = commentList + sortedImports

    return code.replaceRange(
        importList.startOffset,
        importList.endOffset,
        importsWithComments.joinToString(separator = "\n") { imprt -> imprt.text } + "\n",
    )
  }
}
