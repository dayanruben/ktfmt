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
    val (shebang, kotlinCode) = splitShebang(code)
    checkEscapeSequences(kotlinCode)

    return kotlinCode
        .let { convertLineSeparators(it) }
        .let { sortedAndDistinctImports(it) }
        .let { dropRedundantElements(it, options) }
        .let { addRedundantElements(it, options) }
        .let { prettyPrint(it, options, lineSeparator = "\n") }
        .let { addRedundantElements(it, options) }
        .let { MultilineStringFormatter(options.continuationIndent).format(it) }
        .let { convertLineSeparators(it, checkNotNull(Newlines.guessLineSeparator(kotlinCode))) }
        .let { if (shebang.isEmpty()) it else shebang + "\n" + it }
  }

  /**
   * format formats the Kotlin code in 'code', applying only replacements that intersect the given
   * character ranges.
   */
  @JvmStatic
  @Throws(FormatterException::class, ParseError::class)
  fun format(
      options: FormattingOptions,
      code: String,
      characterRanges: Collection<Range<Int>>,
  ): String {
    if (characterRanges.isEmpty()) {
      return code
    }
    return applyIntersectingLineReplacements(code, format(options, code), characterRanges)
  }

  /** prettyPrint reflows 'code' using google-java-format's engine. */
  private fun prettyPrint(
      code: String,
      options: FormattingOptions,
      lineSeparator: String,
  ): String {
    val file = Parser.parse(code)
    val kotlinInput = KotlinInput(code, file)
    val javaOutput =
        JavaOutput(lineSeparator, kotlinInput, KDocCommentsHelper(lineSeparator, options.maxWidth))
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

    val tokenRangeSet =
        kotlinInput.characterRangesToTokenRanges(listOf(Range.closedOpen(0, code.length)))
    return WhitespaceTombstones.replaceTombstoneWithTrailingWhitespace(
        JavaOutput.applyReplacements(code, javaOutput.getFormatReplacements(tokenRangeSet))
    )
  }

  private fun splitShebang(code: String): Pair<String, String> =
      if (code.startsWith("#!")) {
        code.split("\n".toRegex(), limit = 2).let { it[0] to it[1] }
      } else {
        "" to code
      }

  private fun applyIntersectingLineReplacements(
      original: String,
      formatted: String,
      characterRanges: Collection<Range<Int>>,
  ): String {
    val originalLines = original.toLinesWithOffsets()
    val formattedLines = formatted.toLinesWithOffsets()
    val replacements = mutableListOf<LineReplacement>()
    val lcs = buildLcsTable(originalLines, formattedLines)
    var originalIndex = 0
    var formattedIndex = 0
    while (originalIndex < originalLines.size || formattedIndex < formattedLines.size) {
      if (
          originalIndex < originalLines.size &&
              formattedIndex < formattedLines.size &&
              originalLines[originalIndex].text == formattedLines[formattedIndex].text
      ) {
        originalIndex++
        formattedIndex++
        continue
      }

      val originalStart = originalIndex
      val formattedStart = formattedIndex
      while (
          (originalIndex < originalLines.size || formattedIndex < formattedLines.size) &&
              !(originalIndex < originalLines.size &&
                  formattedIndex < formattedLines.size &&
                  originalLines[originalIndex].text == formattedLines[formattedIndex].text)
      ) {
        if (
            formattedIndex == formattedLines.size ||
                originalIndex < originalLines.size &&
                    lcs[originalIndex + 1][formattedIndex] >= lcs[originalIndex][formattedIndex + 1]
        ) {
          originalIndex++
        } else {
          formattedIndex++
        }
      }
      val startOffset = originalLines.getOrNull(originalStart)?.startOffset ?: original.length
      val endOffset =
          originalLines.getOrNull(originalIndex - 1)?.endOffset
              ?: originalLines.getOrNull(originalStart)?.startOffset
              ?: original.length
      if (characterRanges.any { it.intersects(startOffset, endOffset) }) {
        replacements.add(
            LineReplacement(
                startOffset,
                endOffset,
                formattedLines.subList(formattedStart, formattedIndex).joinToString("") { it.text },
            )
        )
      }
    }

    return replacements.asReversed().fold(original) { text, replacement ->
      text.replaceRange(replacement.startOffset, replacement.endOffset, replacement.text)
    }
  }

  private fun buildLcsTable(
      originalLines: List<LineWithOffset>,
      formattedLines: List<LineWithOffset>,
  ): Array<IntArray> {
    val lcs = Array(originalLines.size + 1) { IntArray(formattedLines.size + 1) }
    for (originalIndex in originalLines.indices.reversed()) {
      for (formattedIndex in formattedLines.indices.reversed()) {
        lcs[originalIndex][formattedIndex] =
            if (originalLines[originalIndex].text == formattedLines[formattedIndex].text) {
              lcs[originalIndex + 1][formattedIndex + 1] + 1
            } else {
              maxOf(lcs[originalIndex + 1][formattedIndex], lcs[originalIndex][formattedIndex + 1])
            }
      }
    }
    return lcs
  }

  private fun String.toLinesWithOffsets(): List<LineWithOffset> {
    val lines = mutableListOf<LineWithOffset>()
    var startOffset = 0
    forEachIndexed { index, char ->
      if (char == '\n') {
        lines.add(LineWithOffset(substring(startOffset, index + 1), startOffset, index + 1))
        startOffset = index + 1
      }
    }
    if (startOffset < length) {
      lines.add(LineWithOffset(substring(startOffset), startOffset, length))
    }
    return lines
  }

  private fun Range<Int>.intersects(startOffset: Int, endOffset: Int): Boolean =
      if (startOffset == endOffset) {
        contains(startOffset)
      } else {
        val replacementRange = Range.closedOpen(startOffset, endOffset)
        isConnected(replacementRange) && !intersection(replacementRange).isEmpty
      }

  private data class LineWithOffset(
      val text: String,
      val startOffset: Int,
      val endOffset: Int,
  )

  private data class LineReplacement(
      val startOffset: Int,
      val endOffset: Int,
      val text: String,
  )

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
