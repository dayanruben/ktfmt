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

package org.jetbrains.kotlinx.ktfmt.format

import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.psi.KtAnnotation
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/** Sorts declaration keywords according to the Kotlin coding conventions. */
internal object ModifierSorter {
  // https://kotlinlang.org/docs/coding-conventions.html#modifiers-order
  private val ranks =
      listOf(
              setOf("public", "protected", "private", "internal"),
              setOf("expect", "actual"),
              setOf("final", "open", "abstract", "sealed", "const"),
              setOf("external"),
              setOf("override"),
              setOf("lateinit"),
              setOf("tailrec"),
              setOf("vararg"),
              setOf("suspend"),
              setOf("inner"),
              setOf("enum", "annotation", "fun"),
              setOf("companion"),
              setOf("inline", "value"),
              setOf("infix"),
              setOf("operator"),
              setOf("data"),
          )
          .flatMapIndexed { rank, keywords -> keywords.map { it to rank } }
          .toMap()

  fun sort(file: KtFile): String {
    val result = lazy(LazyThreadSafetyMode.NONE) { StringBuilder(file.text) }
    file.accept(
        object : KtTreeVisitorVoid() {
          override fun visitModifierList(list: KtModifierList) {
            // Sort nested modifiers before moving an annotation that might contain them.
            super.visitModifierList(list)
            sortList(list, result)
          }
        },
    )
    return if (result.isInitialized()) result.value.toString() else file.text
  }

  private fun sortList(list: KtModifierList, result: Lazy<StringBuilder>) {
    val segment = mutableListOf<ASTNode>()
    fun finishSegment() {
      if (segment.size > 1) {
        val sorted = segment.sortedBy { requireNotNull(rank(it)) }
        if (sorted != segment) {
          val code = result.value
          val sortedText = buildString {
            for ((index, modifier) in sorted.withIndex()) {
              if (index > 0) {
                append(code, segment[index - 1].textRange.endOffset, segment[index].startOffset)
              }
              append(code, modifier.startOffset, modifier.textRange.endOffset)
            }
          }
          // Reuse the whitespace between tokens to preserve this segment's length.
          code.replace(segment.first().startOffset, segment.last().textRange.endOffset, sortedText)
        }
      }
      segment.clear()
    }

    for (child in generateSequence(list.node.firstChildNode) { it.treeNext }) {
      when {
        child.psi is PsiWhiteSpace -> continue
        rank(child) != null -> segment.add(child)
        // Keep comments, context parameters, and unsupported modifiers in place.
        else -> finishSegment()
      }
    }
    finishSegment()
  }

  private fun rank(node: ASTNode): Int? =
      when {
        node.psi is KtAnnotation || node.psi is KtAnnotationEntry -> -1
        node.elementType is KtModifierKeywordToken -> ranks[node.text]
        else -> null
      }
}
