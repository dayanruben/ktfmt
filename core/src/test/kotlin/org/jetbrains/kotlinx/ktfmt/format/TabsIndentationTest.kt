package org.jetbrains.kotlinx.ktfmt.format

import com.google.common.collect.Range
import com.google.common.collect.TreeRangeSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** File-based cases cannot set [FormattingOptions.useTabsForIndentation], so cover it here. */
class TabsIndentationTest {
  @Test
  fun `can emit tabs for leading indentation`() {
    val code = "fun f() { if (true) { println(\"yes\") } }\n"

    assertEquals(
        "fun f() {\n\tif (true) {\n\t\tprintln(\"yes\")\n\t}\n}\n",
        Formatter.format(
            Formatter.META_FORMAT.copy(useTabsForIndentation = true),
            KotlinCode.from(code, FileType.REGULAR),
        ),
    )
  }

  @Test
  fun `raw string whitespace keeps its original value with tabs enabled`() {
    val quotes = "\"\"\""
    val content = "one\n    two\n  "
    val code = "fun f() {\n  val raw = $quotes$content$quotes\n}\n"
    val options = Formatter.META_FORMAT.copy(useTabsForIndentation = true)
    val formatted = Formatter.format(options, KotlinCode.from(code, FileType.REGULAR))

    assertTrue(formatted.startsWith("fun f() {\n\t"))
    assertEquals(content, formatted.substringAfter(quotes).substringBefore(quotes))
    assertEquals(formatted, Formatter.format(options, KotlinCode.from(formatted, FileType.REGULAR)))
  }

  @Test
  fun `tab indentation aligns KDoc and keeps the value of trimmed strings`() {
    val quotes = "\"\"\""
    val code =
        """
        |fun f() {
        |  /**
        |   * Example
        |   *
        |   * Description
        |   */
        |  val example =
        |      $quotes
        |      one
        |        two
        |      $quotes.trimIndent()
        |}
        |"""
            .trimMargin()
    val options = Formatter.META_FORMAT.copy(useTabsForIndentation = true)
    val formatted = Formatter.format(options, KotlinCode.from(code, FileType.REGULAR))

    assertTrue(formatted.contains("\t/**\n\t * Example\n"))
    assertEquals(
        "one\n  two",
        formatted.substringAfter(quotes).substringBefore(quotes).trimIndent(),
    )
    assertEquals(formatted, Formatter.format(options, KotlinCode.from(formatted, FileType.REGULAR)))
  }

  @Test
  fun `partial formatting uses tabs on the selected line`() {
    val code = KotlinCode.from("fun f() {\n  val a   = 1\n  val b   = 2\n}\n", FileType.REGULAR)
    val lineRanges = TreeRangeSet.create<Int>().apply { add(Range.closedOpen(1, 2)) }
    val options = Formatter.META_FORMAT.copy(useTabsForIndentation = true)

    assertEquals(
        "fun f() {\n\tval a = 1\n  val b   = 2\n}\n",
        Formatter.format(options, code, code.lineRangesToCharRanges(lineRanges)),
    )
  }

  @Test
  fun `tab indentation preserves remainder spaces`() {
    val code =
        """
        |fun f() {
        |  val result =
        |      listOf(
        |          "one",
        |          "two",
        |      )
        |}
        |"""
            .trimMargin()

    val options =
        FormattingOptions(
            blockIndent = 4,
            continuationIndent = 2,
            useTabsForIndentation = true,
        )

    assertEquals(
        "fun f() {\n" +
            "\tval result =\n" +
            "\t  listOf(\n" +
            "\t\t\"one\",\n" +
            "\t\t\"two\",\n" +
            "\t  )\n" +
            "}\n",
        Formatter.format(options, KotlinCode.from(code, FileType.REGULAR)),
    )
  }
}
