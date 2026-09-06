package org.jetbrains.ktfmt.format

import org.junit.jupiter.api.Assertions.assertEquals
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
