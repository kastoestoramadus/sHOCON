package org.ekrich.config.impl

import org.junit.*
import org.junit.Assert.*

import java.{util => ju}

import org.ekrich.config.ConfigFactory
import org.ekrich.config.ConfigFormatOptions
import org.ekrich.config.ConfigParseOptions
import org.ekrich.config.ConfigResolveOptions

import scala.jdk.CollectionConverters.*
import org.ekrich.config.ConfigRenderOptions

// Regression tests for rendering old behaviour compatibility
class ConfigDefaultRenderingTest extends RenderingTestSuite {
  private implicit val defaultFormatOptions: ConfigFormatOptions =
    ConfigFormatOptions.defaults

  @Test
  def newLineAtTheEnd(): Unit = {
    val in = """r {
               |}""".stripMargin
    val result = formatHocon(in)
    val expected = """r {}
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def useFourSpacesIndentation(): Unit = {
    val in = """r {
               |  p {
               |        d {
               |        s: ${r.ss}
               |        }
               |     }
               |}""".stripMargin
    val result = formatHocon(in)

    val expected = """r {
                     |    p {
                     |        d {
                     |            s = ${r.ss}
                     |        }
                     |    }
                     |}
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def useEqualsAsAssignSign(): Unit = {
    val in = """r {
               |    s=t_f
               |      "n-m"=1
               |    n:"ALA"
               |}""".stripMargin
    val result = formatHocon(in)

    val expected = """r {
                     |    n = ALA
                     |    n-m = 1
                     |    s = t_f
                     |}
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def dontSimplifyOneEntryNestedObjects(): Unit = {
    val in = """r.p.d= 42"""
    val result = formatHocon(in)

    val expected =
      """r {
        |    p {
        |        d = 42
        |    }
        |}
        |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def properArrayConcat(): Unit = {
    val in =
      """except: ${ex1} ${ex2}
        |myEmpty: " "
        |""".stripMargin
    val result = formatHocon(in)

    val expected =
      """except = ${ex1} ${ex2}
        |myEmpty = " "
        |""".stripMargin
    checkEqualsAndStable(expected, result)
  }
  // an object nested inside an array is never itself "at root" - it always
  // needs its own braces, first entry included. Porting lightbend/config#832.
  @Test
  def listElementsKeepTheirBraces(): Unit = {
    val in = """root = [{foo = bar}, {baz = qux}]"""
    val result = formatHocon(in)

    val expected = """root = [
                     |    {
                     |        foo = bar
                     |    },
                     |    {
                     |        baz = qux
                     |    }
                     |]
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  // the space between two substitutions in a concatenation is unquoted in
  // the source; re-quoting it on render turns a valid array concatenation
  // into a parse error on the way back in. Porting lightbend/config#841.
  @Test
  def arrayConcatenationRoundTripsThroughRender(): Unit = {
    val in = """ex1 = [1, 2]
               |except = ${ex1} ${ex1}""".stripMargin
    val result = formatHocon(in)

    val resolved =
      ConfigFactory.parseString(result, ConfigParseOptions.defaults).resolve()
    assertEquals(List(1, 2, 1, 2), resolved.getIntList("except").asScala)
  }

  // An unresolved merge under a key renders as repeated key/value entries. The
  // banner it used to carry parsed back as comments on those values, so every
  // pass re-emitted them and added one of its own.
  @Test
  def unresolvedMergesRenderToAFixedPoint(): Unit = {
    val inputs = List(
      """a : [1]
        |a += 2""".stripMargin,
      """a : 1
        |a : ${a}""".stripMargin,
      """path = [ /bin ]
        |path = ${path} [ /usr/bin ]""".stripMargin,
      """path : "a:b:c"
        |path : ${path}":d"""".stripMargin,
      """foo : { a : { c : 1 } }
        |foo : ${foo.a}
        |foo : { a : 2 }""".stripMargin,
      """a : 1
        |b : 2
        |a : ${b}
        |b : ${a}""".stripMargin,
      """# one
        |a : 1
        |# two
        |a : ${a}""".stripMargin
    )
    inputs.foreach { in =>
      val result = formatHocon(in)
      checkReparses(result)
      checkEqualObjects(result, formatHocon(result))
    }
  }

  @Test
  def commentsStayWithTheirMergedValue(): Unit = {
    val in = """# one
               |a : 1
               |# two
               |a : ${a}""".stripMargin
    val result = formatHocon(in)

    val expected = """# one
                     |a = 1
                     |# two
                     |a = ${a}
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def nestedUnresolvedMergeIndentsLikeItsSiblings(): Unit = {
    val in = """outer {
               |  sib : 0
               |  a : 1
               |  a : ${outer.a}
               |}""".stripMargin
    val result = formatHocon(in)

    val expected = """outer {
                     |    a = 1
                     |    a = ${outer.a}
                     |    sib = 0
                     |}
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def commentsOnTheMergeItselfSurvive(): Unit = {
    val root = ConfigFactory.parseString("a : 1\na : ${a}", parseOptions).root
    val merge = root.get("a")
    val tagged =
      merge.withOrigin(
        merge.origin.withComments(ju.Collections.singletonList("kept"))
      )
    val result = root
      .withValue("a", tagged)
      .render(
        myDefaultRenderOptions.setConfigFormatOptions(defaultFormatOptions)
      )

    val expected = """# kept
                     |a = 1
                     |a = ${a}
                     |""".stripMargin
    checkEqualObjects(expected, result)
  }

  // A merge stack is written as repeated fields, so its keys and separators
  // come from the render options like those of any other field.
  // The mergeStack* cases below are not from lightbend/config: they pin that
  // merge stack entries are written with the render options' key and
  // separator (ConfigDelayedMerge.render). Revisit and drop them when upstream
  // adds equivalent coverage.
  @Test
  def mergeStackUsesTheDefaultSeparator(): Unit = {
    val in = """a : 1
               |a : ${a}
               |sib : 0""".stripMargin
    val result = formatHocon(in)

    val expected = """a = 1
                     |a = ${a}
                     |sib = 0
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def mergeStackUsesTheCompactSeparator(): Unit = {
    val in = """a : 1
               |a : ${a}
               |sib : 0""".stripMargin
    val result = ConfigFactory
      .parseString(in, parseOptions)
      .root
      .render(ConfigRenderOptions.concise.setJson(false))

    checkEqualObjects("a=1,a=${a},sib=0", result)
  }

  @Test
  def mergeStackQuotesKeysThatNeedIt(): Unit = {
    val in = """"a b" : 1
               |"a b" : ${x}""".stripMargin
    val result = formatHocon(in)

    val expected = """"a b" = 1
                     |"a b" = ${x}
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def mergeStackInJsonKeepsQuotedKeysAndColons(): Unit = {
    val in = """a : 1
               |a : ${a}
               |sib : 0""".stripMargin
    val result = ConfigFactory
      .parseString(in, parseOptions)
      .root
      .render(ConfigRenderOptions.concise)

    checkEqualObjects("""{"a":1,"a":${a},"sib":0}""", result)
  }

  // `+=` wraps its value in a list, and the comment above the field went to
  // the wrapped element as well as to the concatenation, so it printed twice.
  // The commentOnPlusEquals* cases below are not from lightbend/config: they
  // pin that a comment above a += field attaches to the field, not also to its
  // element (ConfigParser). Revisit and drop them when upstream adds the
  // coverage.
  @Test
  def commentOnPlusEqualsIsRenderedOnce(): Unit = {
    val in = """# two
               |a += 2""".stripMargin
    val result = formatHocon(in)

    val expected = """# two
                     |a = ${?a}[
                     |    2
                     |]
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def commentOnPlusEqualsAfterADefinitionIsRenderedOnce(): Unit = {
    val in = """a : [1]
               |# two
               |a += 2""".stripMargin
    val result = formatHocon(in)

    assertEquals(1, result.split("# two", -1).length - 1)
    checkEqualObjects(result, formatHocon(result))
  }

  @Test
  def commentOnPlusEqualsSurvivesResolve(): Unit = {
    val in = """a : [1]
               |# two
               |a += 2""".stripMargin
    val resolved = ConfigFactory.parseString(in, parseOptions).resolve()

    assertEquals(
      List(" two"),
      resolved.getValue("a").origin.comments.asScala.toList
    )
  }

  @Test
  def commentOnPlusEqualsOfAnObjectIsRenderedOnce(): Unit = {
    val in = """# two
               |a += { x = 1 }""".stripMargin
    val result = formatHocon(in)

    val expected = """# two
                     |a = ${?a}[
                     |    {
                     |        x = 1
                     |    }
                     |]
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  // resolved, the comment stays above the field and no longer above 2
  @Test
  def commentOnPlusEqualsIsRenderedOnceAfterResolve(): Unit = {
    val in = """a : [1]
               |# two
               |a += 2""".stripMargin
    val result = ConfigFactory
      .parseString(in, parseOptions)
      .resolve()
      .root
      .render(
        myDefaultRenderOptions.setConfigFormatOptions(defaultFormatOptions)
      )

    val expected = """# two
                     |a = [
                     |    1,
                     |    2
                     |]
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }
  // with no key to spell it out as repeated entries, a merge can only be
  // described
  @Test
  def unresolvedMergeRenderedWithoutAKeyIsDescribed(): Unit = {
    val merge =
      ConfigFactory.parseString("a : 1\na : ${a}", parseOptions).root.get("a")
    val options =
      myDefaultRenderOptions.setConfigFormatOptions(defaultFormatOptions)

    val expected =
      """# unresolved merge of 2 values follows (
        |# this unresolved merge will not be parseable because it's at the root of the object
        |# the HOCON format has no way to list multiple root objects in a single file
        |#     unmerged value 0 from String: 1
        |1,
        |#     unmerged value 1 from String: 2
        |${a}
        |# ) end of unresolved merge
        |""".stripMargin
    checkEqualObjects(expected, merge.render(options))
    checkEqualObjects("1,\n${a}\n", merge.render(options.setComments(false)))
  }

  @Test
  def unresolvedMergeInsideArrayElementRendersToAFixedPoint(): Unit = {
    val in = """l = [ { a : 1
               |a : ${x} } ]""".stripMargin
    val result = formatHocon(in)

    val expected = """l = [
                     |    {
                     |        a = 1
                     |        a = ${x}
                     |    }
                     |]
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def commentsOfNestedUnresolvedMergeIndentLikeItsSiblings(): Unit = {
    val in = """outer {
               |  sib : 0
               |  # c1
               |  a : 1
               |  # c2
               |  a : ${outer.a}
               |}""".stripMargin
    val result = formatHocon(in)

    val expected = """outer {
                     |    # c1
                     |    a = 1
                     |    # c2
                     |    a = ${outer.a}
                     |    sib = 0
                     |}
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  // the [] list-expansion suffix (lightbend/config#833) is part of the
  // substitution syntax, not resolved by rendering, so it stays verbatim
  @Test
  def envVarListExpansionSubstitutionRendersVerbatim(): Unit = {
    val in = """a = ${FOO[]}
               |b = ${?FOO[]}""".stripMargin
    val result = formatHocon(in)
    val expected = """a = ${FOO[]}
                     |b = ${?FOO[]}
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  // lightbend/config#800: the nested merge a partial resolve left behind has
  // no spelling in HOCON, so the render could not be read back
  // The partiallyResolved* cases below are not from lightbend/config: they pin
  // that a partially resolved value that cannot become an object hides the
  // merge stack below it (ConfigDelayedMerge.cannotBecomeAnObject). Revisit
  // and drop them when upstream adds equivalent coverage.
  @Test
  def partiallyResolvedAppendsRenderAsOneList(): Unit = {
    val in = """a = [${s}-1]
               |a += ${s}-2
               |a += ${s}-3
               |a += ${s}-4""".stripMargin
    val partial = ConfigFactory
      .parseString(in, parseOptions)
      .resolve(ConfigResolveOptions.defaults.setAllowUnresolved(true))
    val result = partial.root.render(
      myDefaultRenderOptions.setConfigFormatOptions(defaultFormatOptions)
    )

    val expected = """a = [
                     |    ${s}-1,
                     |    ${s}-2,
                     |    ${s}-3,
                     |    ${s}-4
                     |]
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def partiallyResolvedSelfReferencesRenderAsOneValue(): Unit = {
    val in = """a = ${s}-1
               |a = ${a}-2
               |a = ${a}-3
               |b = ${base}
               |b += ${s}-2
               |b += ${s}-3""".stripMargin
    val partial = ConfigFactory
      .parseString(in, parseOptions)
      .resolve(ConfigResolveOptions.defaults.setAllowUnresolved(true))
    val result = partial.root.render(
      myDefaultRenderOptions.setConfigFormatOptions(defaultFormatOptions)
    )

    val expected = """a = ${s}"-1-2-3"
                     |b = ${base}[
                     |    ${s}-2,
                     |    ${s}-3
                     |]
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def repeatedMergeEntriesUseFieldSpacingForAppendedList(): Unit = {
    val in = """a : [1]
               |a += 2""".stripMargin
    val result = formatHocon(in)

    val expected = """a = [
                     |    1
                     |]
                     |a = ${?a}[
                     |    2
                     |]
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }

  @Test
  def repeatedMergeEntriesUseFieldSpacingForNestedAppendedList(): Unit = {
    val in = """o { a : [1]
               |a += 2 }""".stripMargin
    val result = formatHocon(in)

    val expected = """o {
                     |    a = [
                     |        1
                     |    ]
                     |    a = ${?o.a}[
                     |        2
                     |    ]
                     |}
                     |""".stripMargin
    checkEqualsAndStable(expected, result)
  }
}
