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

import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/** Splits safe, long ordinary string literals before the normal pretty-printer runs. */
internal class LongStringLiteralFormatter(
    private val maxWidth: Int,
    private val continuationIndent: Int,
) {
  fun format(code: String): String {
    val replacements = getLongStringLiteralReplacements(code)
    if (replacements.isEmpty()) return code

    val result = StringBuilder(code)
    for (replacement in replacements.sortedByDescending { it.startOffset }) {
      result.replace(replacement.startOffset, replacement.endOffset, replacement.text)
    }
    return result.toString()
  }

  private fun getLongStringLiteralReplacements(code: String): List<LongStringLiteralReplacement> {
    val file = Parser.parse(code)
    val replacements = mutableListOf<LongStringLiteralReplacement>()
    file.accept(
        object : KtTreeVisitorVoid() {
          override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
            super.visitStringTemplateExpression(expression)
            if (!expression.isSafeReplacementContext()) return
            val replacement = expression.text.splitLongStringLiteral() ?: return
            replacements.add(
                LongStringLiteralReplacement(
                    startOffset = expression.startOffset,
                    endOffset = expression.endOffset,
                    text = replacement,
                )
            )
          }
        }
    )
    return replacements
  }

  private fun KtStringTemplateExpression.isSafeReplacementContext(): Boolean =
      when (parent) {
        is KtBinaryExpression,
        is KtNamedFunction,
        is KtProperty,
        is KtPropertyAccessor,
        is KtReturnExpression,
        is KtValueArgument -> true
        else -> false
      }

  private fun String.splitLongStringLiteral(): String? {
    if (length <= maxWidth) return null
    if (!startsWith("\"") || !endsWith("\"") || startsWith("\"\"\"")) return null
    if (any { it == '\n' || it == '\r' || it == '$' || it == '\\' }) return null

    val content = substring(1, length - 1)
    val maxContentLength = maxWidth - continuationIndent - STRING_OVERHEAD
    if (maxContentLength < MIN_SPLIT_CONTENT_LENGTH || content.length <= maxContentLength) {
      return null
    }

    val parts = mutableListOf<String>()
    var start = 0
    while (start < content.length) {
      if (content.length - start <= maxContentLength) {
        parts.add(content.substring(start))
        break
      }

      val split = content.findSplit(start, start + maxContentLength) ?: return null
      parts.add(content.substring(start, split))
      start = split
    }

    if (parts.size < 2) return null
    return parts.joinToString(" + ") { "\"$it\"" }
  }

  private fun String.findSplit(start: Int, preferredEnd: Int): Int? {
    var split = lastIndexOf(' ', preferredEnd.coerceAtMost(length - 1))
    if (split < start) return null

    split++
    while (split < length && this[split] == ' ') {
      split++
    }
    return split.takeIf { it > start && it < length }
  }

  private data class LongStringLiteralReplacement(
      val startOffset: Int,
      val endOffset: Int,
      val text: String,
  )

  private companion object {
    const val MIN_SPLIT_CONTENT_LENGTH = 16
    const val STRING_OVERHEAD = 5
  }
}
