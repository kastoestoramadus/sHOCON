package org.ekrich.config.scala3

import scala.compiletime.testing.typeChecks
import scala.concurrent.duration.*

import org.ekrich.config.*
import org.junit.Assert.*
import org.junit.Test

import ConfigReadError.*
import TestSupport.intercept

class ScalaConfigTest {
  private def parse(s: String): Config = ConfigFactory.parseString(s)

  private val sample = parse("""
    s = "text"
    i = 42
    big = 3000000000
    d = 1.5
    b = true
    n = null
    o { x = 1, y = 2 }
    l = [1, 2, 3]
    strs = [a, b, a]
    holes = [1, null, 3]
    mixed = [1, two, 3]
    dur = 1500 ms
    mem = 2 MiB
  """)
  private val api = sample.asScalaConfig

  // (a) unresolved config: the overlay keeps the very same value

  private val unresolvedText = """
    # where the broker lives
    broker.host = ${env.broker}
    # the port
    broker.port = 5672
  """
  private def unresolved: Config = ConfigFactory.parseString(
    unresolvedText,
    ConfigParseOptions.defaults.setOriginDescription("app.conf")
  )

  @Test
  def asConfigKeepsOriginCommentsAndUnresolvedExpressions(): Unit = {
    val conf = unresolved
    val back = conf.asScalaConfig.asConfig
    assertFalse("must not resolve", back.isResolved)
    assertTrue(
      back.root.origin.description,
      back.root.origin.description.startsWith("app.conf")
    )
    val port = back.getValue("broker.port").origin
    assertEquals(conf.getValue("broker.port").origin, port)
    assertEquals(1, port.comments.size)
    assertEquals("the port", port.comments.get(0).trim)
    val rendered = back.root.render(ConfigRenderOptions.defaults)
    assertTrue(rendered, rendered.contains("${env.broker}"))
  }

  @Test
  def readingDoesNotResolve(): Unit = {
    val conf = unresolved
    val a = conf.asScalaConfig
    assertEquals(Right(5672), a.read[Int]("broker.port"))
    assertEquals(Left(Unresolved("broker.host")), a.read[String]("broker.host"))
    assertFalse(conf.isResolved)
  }

  // (b) withFallback

  @Test
  def withFallbackEqualsCore(): Unit = {
    val top = parse("a = 1, o { x = 1 }, l = [1]")
    val bottom = parse("a = 2, o { y = 2 }, c = 3, l = [2]")
    val viaCore = top.withFallback(bottom)
    val viaOverlay =
      top.asScalaConfig.withFallback(bottom.asScalaConfig).asConfig
    assertEquals(viaCore, viaOverlay)
    assertEquals(viaCore.root.render, viaOverlay.root.render)
  }

  @Test
  def withFallbackKeepsUnresolvedUnresolved(): Unit = {
    val top = parse("a = ${x}")
    val bottom = parse("x = 1")
    val merged = top.asScalaConfig.withFallback(bottom.asScalaConfig)
    assertFalse(merged.asConfig.isResolved)
    assertEquals(top.withFallback(bottom), merged.asConfig)
    assertEquals(
      Right(1),
      merged.asConfig.resolve().asScalaConfig.read[Int]("a")
    )
  }

  // (c) one ConfigReadError variant per cause

  @Test
  def missingPath(): Unit = {
    assertEquals(Left(Missing("nope")), api.read[Int]("nope"))
    assertEquals(Left(Missing("o.z")), api.read[Int]("o.z"))
    assertEquals(Left(Missing("s.z")), api.read[Int]("s.z"))
  }

  @Test
  def explicitNull(): Unit = {
    assertEquals(Left(Null("n")), api.read[Int]("n"))
    assertEquals(Left(Null("n")), api.read[String]("n"))
    assertEquals(Left(Null("n")), api.read[List[Int]]("n"))
  }

  @Test
  def wrongType(): Unit = {
    assertEquals(Left(WrongType("s", "NUMBER", "STRING")), api.read[Int]("s"))
    assertEquals(
      Left(WrongType("o", "STRING", "OBJECT")),
      api.read[String]("o")
    )
    assertEquals(
      Left(WrongType("i", "LIST", "NUMBER")),
      api.read[List[Int]]("i")
    )
    assertEquals(
      Left(WrongType("l", "OBJECT", "LIST")),
      api.read[Map[String, Int]]("l")
    )
    assertEquals(
      Left(WrongType("s", "BOOLEAN", "STRING")),
      api.read[Boolean]("s")
    )
  }

  @Test
  def numberOutOfRange(): Unit = {
    assertEquals(
      Left(WrongType("big", "32-bit integer", "out-of-range value 3000000000")),
      api.read[Int]("big")
    )
    assertEquals(Right(3000000000L), api.read[Long]("big"))
  }

  @Test
  def unresolvedSubstitution(): Unit = {
    val u = parse("a = ${x}").asScalaConfig
    assertEquals(Left(Unresolved("a")), u.read[Int]("a"))
    assertEquals(Left(Unresolved("a")), u.readOption[Int]("a"))
    assertEquals(Left(Unresolved("a")), u.lookup("a"))
  }

  @Test
  def otherExceptionsPropagate(): Unit = {
    val c = parse("""d = "10 foo" """).asScalaConfig
    intercept[ConfigException.BadValue](c.read[FiniteDuration]("d"))
    intercept[ConfigException.BadPath](api.read[Int]("a..b"))
    intercept[ConfigException.BadPath](api.readOption[Int]("a..b"))
    intercept[ConfigException.BadPath](api.lookup("a..b"))
    given ConfigReader[Thread] = (_, _) =>
      throw new IllegalStateException("boom")
    intercept[IllegalStateException](api.read[Thread]("s"))
  }

  @Test
  def readersMayReturnOther(): Unit = {
    val cause = new IllegalArgumentException("not a port")
    given ConfigReader[Thread] = (_, path) => Left(Other(path, cause))
    assertEquals(Left(Other("i", cause)), api.read[Thread]("i"))
  }

  @Test
  def readErrorHasPath(): Unit = {
    val errors = Seq(
      Missing("p"),
      Null("p"),
      WrongType("p", "a", "b"),
      Unresolved("p"),
      Other("p", new RuntimeException)
    )
    assertEquals(Seq.fill(5)("p"), errors.map(_.path))
  }

  // the null / missing contract

  @Test
  def readOptionTreatsMissingAndNullAsNone(): Unit = {
    assertEquals(Right(None), api.readOption[Int]("nope"))
    assertEquals(Right(None), api.readOption[Int]("n"))
    assertEquals(Right(None), api.readOption[Int]("s.z"))
    assertEquals(Right(Some(42)), api.readOption[Int]("i"))
  }

  @Test
  def readOptionKeepsTypeErrorsVisible(): Unit = {
    assertEquals(
      Left(WrongType("s", "NUMBER", "STRING")),
      api.readOption[Int]("s")
    )
    assertEquals(
      Left(WrongType("o", "STRING", "OBJECT")),
      api.readOption[String]("o")
    )
  }

  @Test
  def lookupIsLossless(): Unit = {
    assertEquals(Right(Lookup.Missing), api.lookup("nope"))
    assertEquals(Right(Lookup.Null), api.lookup("n"))
    assertEquals(Right(Lookup.Value(sample.getValue("s"))), api.lookup("s"))
    api.lookup("o") match {
      case Right(Lookup.Value(v)) =>
        assertSame(sample.getValue("o"), v)
        assertEquals(ConfigValueType.OBJECT, v.valueType)
      case other => fail("expected the object, got " + other)
    }
  }

  // values

  @Test
  def readsScalars(): Unit = {
    assertEquals(Right("text"), api.read[String]("s"))
    assertEquals(Right(42), api.read[Int]("i"))
    assertEquals(Right(42L), api.read[Long]("i"))
    assertEquals(Right(1.5), api.read[Double]("d"))
    assertEquals(Right(true), api.read[Boolean]("b"))
    assertEquals(Right(1500.millis), api.read[FiniteDuration]("dur"))
    assertEquals(
      Right(java.time.Duration.ofMillis(1500)),
      api.read[java.time.Duration]("dur")
    )
    assertEquals(
      Right(ConfigMemorySize.ofBytes(2L * 1024 * 1024)),
      api.read[ConfigMemorySize]("mem")
    )
  }

  @Test
  def readsImmutableCollections(): Unit = {
    assertEquals(Right(List(1, 2, 3)), api.read[List[Int]]("l"))
    assertEquals(Right(Set("a", "b")), api.read[Set[String]]("strs"))
    assertEquals(
      Right(Map("x" -> 1, "y" -> 2)),
      api.read[Map[String, Int]]("o")
    )
    val nested = parse("m { a = [1, 2], b = [] }").asScalaConfig
    assertEquals(
      Right(Map("a" -> List(1, 2), "b" -> Nil)),
      nested.read[Map[String, List[Int]]]("m")
    )
    val grid = parse("g = [[1, 2], [3]]").asScalaConfig
    assertEquals(
      Right(List(List(1, 2), List(3))),
      grid.read[List[List[Int]]]("g")
    )
  }

  @Test
  def collectionErrorsNameTheElement(): Unit = {
    assertEquals(Left(Null("holes[1]")), api.read[List[Int]]("holes"))
    assertEquals(
      Left(WrongType("mixed[1]", "NUMBER", "STRING")),
      api.read[List[Int]]("mixed")
    )
    assertEquals(
      Left(WrongType("o.x", "OBJECT", "NUMBER")),
      api.read[Map[String, Map[String, Int]]]("o")
    )
    val dotted = parse("""m { "a.b" = oops }""").asScalaConfig
    assertEquals(
      Left(WrongType("""m."a.b"""", "NUMBER", "STRING")),
      dotted.read[Map[String, Int]]("m")
    )
  }

  @Test
  def mapKeysWithDotsSurvive(): Unit = {
    val dotted = parse("""m { "a.b" = 1, c = 2 }""").asScalaConfig
    assertEquals(
      Right(Map("a.b" -> 1, "c" -> 2)),
      dotted.read[Map[String, Int]]("m")
    )
  }

  // the root has no path; readRoot reads it and reports paths relative to it

  @Test
  def readRootReadsTheWholeConfig(): Unit = {
    assertEquals(
      Right(Map("a" -> 1, "b" -> 2)),
      parse("a = 1, b = 2").asScalaConfig.readRoot[Map[String, Int]]
    )
    assertEquals(
      Right(Map("head" -> Map("ctdi" -> 1.5))),
      parse("head { ctdi = 1.5 }").asScalaConfig
        .readRoot[Map[String, Map[String, Double]]]
    )
    assertEquals(
      Right(Map.empty[String, Int]),
      ConfigFactory.empty("empty").asScalaConfig.readRoot[Map[String, Int]]
    )
  }

  @Test
  def readRootErrorsAreRelativeToTheRoot(): Unit = {
    val c = parse("a = 1, o { x = text }").asScalaConfig
    assertEquals(
      Left(WrongType("o", "NUMBER", "OBJECT")),
      c.readRoot[Map[String, Int]]
    )
    assertEquals(
      Left(Null("n")),
      parse("n = null").asScalaConfig.readRoot[Map[String, Int]]
    )
    assertEquals(
      Left(WrongType("o.x", "NUMBER", "STRING")),
      parse("o { x = text }").asScalaConfig
        .readRoot[Map[String, Map[String, Int]]]
    )
    assertEquals(
      Left(Unresolved("a")),
      parse("a = ${x}").asScalaConfig.readRoot[Map[String, Int]]
    )
  }

  // (d) the same object, in both directions

  @Test
  def asConfigIsTheSameObject(): Unit = {
    assertSame(sample, sample.asScalaConfig.asConfig)
    assertSame(sample, sample.asScalaConfig.asConfig.asScalaConfig.asConfig)
    val empty = ConfigFactory.empty("empty")
    assertSame(empty, empty.asScalaConfig.asConfig)
  }

  // (e) -Yexplicit-nulls is on for this module and user code needs no .nn

  @Test
  def explicitNullsAreOn(): Unit = {
    assertFalse(typeChecks("val s: String = null"))
    assertTrue(typeChecks("val s: String | scala.Null = null"))
  }

  @Test
  def userCodeNeedsNoNn(): Unit = {
    val host: String = api.read[String]("s").getOrElse("")
    val hosts: List[String] = api.read[List[String]]("strs").getOrElse(Nil)
    val port: Option[Int] = api.readOption[Int]("i").getOrElse(None)
    val back: Config = api.asConfig
    assertEquals(
      ("text", 3, Some(42), true),
      (host, hosts.size, port, back.hasPath("s"))
    )
  }
}
