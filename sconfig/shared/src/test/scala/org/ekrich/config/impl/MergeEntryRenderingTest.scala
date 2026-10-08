package org.ekrich.config.impl

import org.junit.Test
import org.junit.Assert.assertEquals
import org.ekrich.config.{
  ConfigFactory,
  ConfigFormatOptions,
  ConfigParseOptions,
  ConfigRenderOptions
}

class MergeEntryRenderingTest {
  private val parseOptions = ConfigParseOptions.defaults.setAllowMissing(true)
  private val options = ConfigRenderOptions.defaults
    .setJson(false)
    .setOriginComments(false)
    .setComments(true)
    .setFormatted(true)
    .setConfigFormatOptions(
      ConfigFormatOptions.defaults
        .setKeepOriginOrder(true)
        .setDoubleIndent(false)
        .setColonAssign(true)
        .setSimplifyNestedObjects(true)
    )

  private def check(input: String, expected: String): Unit = {
    val original = ConfigFactory.parseString(input, parseOptions)
    val rendered = original.root.render(options)
    val reparsed = ConfigFactory.parseString(rendered, parseOptions)
    assertEquals("unresolved config survives rendering", original, reparsed)
    assertEquals(
      "rendering is a fixed point",
      rendered,
      reparsed.root.render(options)
    )
    assertEquals(
      "repeated entries use ordinary field spacing",
      expected,
      rendered
    )
  }

  @Test def appendedList(): Unit =
    check("a : [1]\na += 2", "a: [\n  1\n]\na: ${?a}[\n  2\n]\n")

  @Test def selfReference(): Unit =
    check("a : 1\na : ${a}", "a: 1\na: ${a}\n")

  @Test def nestedAppendedList(): Unit =
    check(
      "o { a : [1]\na += 2 }",
      "o {\n  a: [\n    1\n  ]\n  a: ${?o.a}[\n    2\n  ]\n}\n"
    )
}
