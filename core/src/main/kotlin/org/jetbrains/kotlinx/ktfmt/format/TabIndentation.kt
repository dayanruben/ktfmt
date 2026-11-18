package org.jetbrains.kotlinx.ktfmt.format

/** Converts between indentation strings and their visual column widths for fixed tab stops. */
internal class TabIndentation(private val tabWidth: Int) {
  fun render(columns: Int): String =
      "\t".repeat(columns / tabWidth) + " ".repeat(columns % tabWidth)

  fun visualLength(text: CharSequence): Int {
    var column = 0
    for (char in text) {
      column += if (char == '\t') tabWidth - column % tabWidth else 1
    }
    return column
  }
}
