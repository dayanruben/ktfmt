package org.jetbrains.ktfmt.format

import com.google.common.collect.Range
import com.google.common.collect.TreeRangeSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers partial (range) formatting through the engine-level
 * [Formatter.format] character-ranges API (issue 573).
 */
class PartialFormatTest {
  @Test
  fun `format only requested character range`() {
    val code =
        """
        |private fun MyComposeFunction() {
        |    Function(
        |        modifier =
        |        Modifier
        |            .padding(vertical = someVerticalPadding())
        |            .padding(vertical = someVerticalPadding())
        |    )
        |}
        |
        |fun untouched  ( value : String ) = value
        |"""
            .trimMargin()

    val startOffset = code.indexOf("Modifier")
    val endOffset = code.indexOf("    )")
    val ranges = TreeRangeSet.create<Int>()
    ranges.add(Range.closedOpen(startOffset, endOffset))

    val formatted =
        Formatter.format(
            Formatter.META_FORMAT,
            KotlinCode.from(code, FileType.REGULAR),
            ranges,
        )

    assertTrue(
        formatted.contains("fun untouched  ( value : String ) = value"),
        "code outside the requested range must stay untouched, got:\n$formatted",
    )
    assertEquals(
        """
        |private fun MyComposeFunction() {
        |  Function(
        |      modifier =
        |          Modifier.padding(vertical = someVerticalPadding())
        |              .padding(vertical = someVerticalPadding()),
        |  )
        |}
        |
        |fun untouched  ( value : String ) = value
        |"""
            .trimMargin(),
        formatted,
    )
  }
}
