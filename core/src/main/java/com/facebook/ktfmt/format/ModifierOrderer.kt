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

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/** Sorts consecutive Kotlin modifier keywords according to the Kotlin coding conventions. */
object ModifierOrderer {
  private data class Replacement(val element: PsiElement, val text: String)

  private val MODIFIER_ORDER =
      listOf(
              "public",
              "protected",
              "private",
              "internal",
              "expect",
              "actual",
              "final",
              "open",
              "abstract",
              "sealed",
              "const",
              "external",
              "override",
              "lateinit",
              "tailrec",
              "vararg",
              "suspend",
              "inner",
              "enum",
              "annotation",
              "fun",
              "companion",
              "inline",
              "value",
              "infix",
              "operator",
              "data",
          )
          .withIndex()
          .associate { it.value to it.index }

  fun reorderModifiers(code: String): String {
    val file = Parser.parse(code)
    val replacements = mutableListOf<Replacement>()

    file.accept(
        object : KtTreeVisitorVoid() {
          override fun visitModifierList(list: KtModifierList) {
            val run = mutableListOf<PsiElement>()

            fun flushRun() {
              if (run.size > 1 && run.all { it.text in MODIFIER_ORDER }) {
                val sortedRun =
                    run.sortedWith(compareBy<PsiElement> { MODIFIER_ORDER.getValue(it.text) })
                run.zip(sortedRun).forEach { (element, sortedElement) ->
                  replacements.add(Replacement(element, sortedElement.text))
                }
              }
              run.clear()
            }

            for (child in list.node.getChildren(null)) {
              val psi = child.psi
              if (psi is PsiWhiteSpace) {
                continue
              }

              if (child.elementType is KtModifierKeywordToken) {
                run.add(psi)
              } else {
                flushRun()
              }
            }
            flushRun()

            super.visitModifierList(list)
          }
        }
    )

    val result = StringBuilder(code)
    for ((element, text) in replacements.sortedByDescending { it.element.startOffset }) {
      result.replace(element.startOffset, element.endOffset, text)
    }

    return result.toString()
  }
}
