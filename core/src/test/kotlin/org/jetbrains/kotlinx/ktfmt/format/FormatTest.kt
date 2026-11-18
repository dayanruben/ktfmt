package org.jetbrains.kotlinx.ktfmt.format

import com.google.common.collect.ImmutableRangeSet
import org.jetbrains.kotlinx.ktfmt.testutil.FormatterTestFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// core/src/test/resources/cases/format
class FormatTest : FormatterTestFactory() {

  @Test
  fun `sort declaration modifiers during whole-file cleanup`() {
    val input = KotlinCode("final public class Example\n", FileType.REGULAR)

    assertEquals(
        "public final class Example\n",
        Formatter.format(DEFAULT_CASE_FORMAT, input, ImmutableRangeSet.of<Int>()),
    )
  }

  @Test
  fun `preserve LF, CRLF and CR line endings`() {
    val lines = listOf("fun main() {", "  println(\"test\")", "}")
    for (ending in listOf("\n", "\r\n", "\r")) {
      val code = lines.joinToString(ending, postfix = ending)

      val reformatted = Formatter.format(DEFAULT_CASE_FORMAT, KotlinCode(code, FileType.REGULAR))
      assertEquals(code, reformatted)
    }
  }
}
