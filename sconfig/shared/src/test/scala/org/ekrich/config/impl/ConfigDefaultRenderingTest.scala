package org.ekrich.config.impl

import org.junit.*
import java.{util => ju}

import org.ekrich.config.ConfigFactory
import org.ekrich.config.ConfigFormatOptions

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
                     |"a" : 1,
                     |# two
                     |"a" : ${a}
                     |
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
                     |    "a" : 1,
                     |    "a" : ${outer.a}
                     |
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
                     |"a" : 1,
                     |"a" : ${a}
                     |
                     |""".stripMargin
    checkEqualObjects(expected, result)
  }
}
