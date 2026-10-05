package org.ekrich.config.scala3

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.ekrich.config.*
import org.junit.Assert.*
import org.junit.Test

import ConfigReadError.*
import TestSupport.intercept

/** Call patterns Scala projects write against the Java-shaped API today, each next to its
  * rewrite with the overlay. The "original" bodies are the quoted code; sources are named in
  * each section. Where the overlay deliberately behaves differently the test says so by name.
  */
class InventoryTest {
  private def parse(s: String): Config = ConfigFactory.parseString(s)

  // S1: pityka/tasks TasksConfig.hostImage; ossuminc/riddl CommonOptionsHelper (flags with a default)

  private def s1Original(raw: Config): Option[String] =
    if (raw.hasPath("hosts.image")) Some(raw.getString("hosts.image")) else None
  private def s1Overlay(raw: Config): Either[ConfigReadError, Option[String]] =
    raw.asScalaConfig.readOption[String]("hosts.image")

  @Test
  def s1_optionalStringViaHasPath(): Unit = {
    for (text <- Seq("hosts.image = img", "hosts.other = 1", "hosts.image = null")) {
      val c = parse(text)
      assertEquals(text, Right(s1Original(c)), s1Overlay(c))
    }
    val bad = parse("hosts.image { a = 1 }")
    intercept[ConfigException.WrongType](s1Original(bad))
    assertEquals(Left(WrongType("hosts.image", "STRING", "OBJECT")), s1Overlay(bad))
  }

  @Test
  def s1_flagWithDefault(): Unit = {
    def original(obj: Config, default: Boolean) =
      if (obj.hasPath("verbose")) obj.getBoolean("verbose") else default
    def overlay(obj: Config, default: Boolean) =
      obj.asScalaConfig.readOption[Boolean]("verbose").map(_.getOrElse(default))
    for (text <- Seq("verbose = true", "verbose = false", "quiet = true", "verbose = null"))
      for (default <- Seq(true, false)) {
        val c = parse(text)
        assertEquals(text, Right(original(c, default)), overlay(c, default))
      }
  }

  @Test
  def s1_durationWithDefault(): Unit = {
    def original(obj: Config, default: FiniteDuration) =
      if (obj.hasPath("max-include-wait"))
        FiniteDuration(obj.getDuration("max-include-wait").toMillis, MILLISECONDS)
      else default
    def overlay(obj: Config, default: FiniteDuration) =
      obj.asScalaConfig.readOption[FiniteDuration]("max-include-wait").map(_.getOrElse(default))
    for (text <- Seq("max-include-wait = 5 seconds", "max-include-wait = 1500 ms", "x = 1")) {
      val c = parse(text)
      assertEquals(text, Right(original(c, 9.seconds)), overlay(c, 9.seconds))
    }
  }

  // S2: oswaldo/hey Settings.RichConfig

  private object Hey {
    implicit class RichConfig(val underlying: Config) {
      def getStringOrElse(path: String, default: => String): String =
        optional(path, underlying.getString).getOrElse(default)
      private def optional[T](path: String, f: String => T): Option[T] =
        if (underlying.hasPath(path)) Some(f(path)) else None
    }
  }

  @Test
  def s2_handWrittenGetStringOrElse(): Unit = {
    import Hey.*
    for (text <- Seq("hey.defaults.serverGroup = prod", "hey.defaults.other = 1", "hey.defaults.serverGroup = null")) {
      val c = parse(text)
      val path = "hey.defaults.serverGroup"
      assertEquals(text, Right(c.getStringOrElse(path, "")), c.asScalaConfig.readOption[String](path).map(_.getOrElse("")))
    }
  }

  // S3: kapunga/fallatol ConfigGetter.optionConfigFetcher

  private def s3Original[A](conf: Config, path: String)(get: (Config, String) => A): Either[Throwable, Option[A]] =
    if (conf.hasPath(path) && !conf.getIsNull(path)) Try(get(conf, path)).toEither.map(Some(_))
    else Right(None)

  @Test
  def s3_optionWithExplicitNullCheck(): Unit = {
    for (text <- Seq("a = 7", "b = 1", "a = null")) {
      val c = parse(text)
      assertEquals(text, s3Original(c, "a")(_.getInt(_)).toOption.get, c.asScalaConfig.readOption[Int]("a").toOption.get)
    }
    // hasPath is already false for an explicit null, so `!getIsNull` is never what decides
    val n = parse("a = null")
    assertFalse(n.hasPath("a"))
    assertTrue(n.hasPathOrNull("a"))
    assertTrue(n.getIsNull("a"))
    // a wrong type is a Left on both sides
    val bad = parse("a = text")
    assertTrue(s3Original(bad, "a")(_.getInt(_)).left.toOption.get.isInstanceOf[ConfigException.WrongType])
    assertEquals(Left(WrongType("a", "NUMBER", "STRING")), bad.asScalaConfig.readOption[Int]("a"))
  }

  // S4: pityka/tasks hostLabels

  private def s4Original(raw: Config): Set[String] =
    if (raw.hasPath("hosts.labels")) raw.getStringList("hosts.labels").asScala.toSet
    else Set.empty[String]
  private def s4Overlay(raw: Config): Either[ConfigReadError, Set[String]] =
    raw.asScalaConfig.readOption[Set[String]]("hosts.labels").map(_.getOrElse(Set.empty))

  @Test
  def s4_stringListToSet(): Unit =
    for (text <- Seq("hosts.labels = [a, b, a]", "hosts.labels = []", "hosts.x = 1", "hosts.labels = [1, b, true]")) {
      val c = parse(text)
      assertEquals(text, Right(s4Original(c)), s4Overlay(c))
    }

  // S5: pityka/tasks hostGPU; sconfig ConfigTest.testNoMergeLists

  @Test
  def s5_boxedIntListToList(): Unit = {
    def original(raw: Config) = raw.getIntList("hosts.gpus").asScala.toList.map(_.toInt)
    for (text <- Seq("hosts.gpus = [0, 1, 3]", "hosts.gpus = []", """hosts.gpus = [1, "2"]""")) {
      val c = parse(text)
      assertEquals(text, Right(original(c)), c.asScalaConfig.read[List[Int]]("hosts.gpus"))
    }
    val merged = parse("a: [1,2], a: [3,4]")
    assertEquals(Seq(3, 4), merged.getIntList("a").asScala)
    assertEquals(Right(Seq(3, 4)), merged.asScalaConfig.read[List[Int]]("a"))
  }

  // S6: kapunga/herna Extractor[Map[String, T]]

  private def s6Original(config: Config, path: String): Map[String, Int] = {
    val c = config.getConfig(path)
    val keys = c.entrySet.asScala.map(_.getKey.split('.').head)
    keys.map(k => k -> c.getInt(k)).toMap
  }

  @Test
  def s6_mapViaEntrySet(): Unit = {
    val c = parse("limits { cpu = 4, ram = 16, gpu = 0 }")
    assertEquals(Right(s6Original(c, "limits")), c.asScalaConfig.read[Map[String, Int]]("limits"))
  }

  @Test
  def s6_overlayKeepsKeysThatContainADot(): Unit = {
    val c = parse("""limits { "cpu.cores" = 4, ram = 16 }""")
    // the original splits the quoted path at the dot and asks for a key that does not exist
    intercept[ConfigException](s6Original(c, "limits"))
    assertEquals(Right(Map("cpu.cores" -> 4, "ram" -> 16)), c.asScalaConfig.read[Map[String, Int]]("limits"))
  }

  // S7: maryknollrad/yader CTDRL

  private def s7Original(c: Config): Either[String, Map[String, (Double, Double)]] =
    Try {
      c.entrySet.asScala.foldLeft(Map.empty[String, (Double, Double)])((m, e) => {
        val ks = e.getKey().split('.')
        if (ks.length != 2) throw new Exception(s"Wrong key format in ctdrl.info : ${e.getKey()}")
        val preValues = m.getOrElse(ks(0), (0.0, 0.0))
        val v = e.getValue().unwrapped match {
          case i: Integer          => i.toDouble
          case d: java.lang.Double => d.toDouble
          case _ => throw new Exception(s"Unsupported type given in ctdrl.info : ${ks(0)} - ${e.getValue().unwrapped}")
        }
        ks(1) match {
          case "ctdi" => m + ((ks(0), (v, preValues._2)))
          case "dlp"  => m + ((ks(0), (preValues._1, v)))
          case _      => throw new Exception(s"Unknown ctdrl.info value : ${ks(1)}")
        }
      })
    }.toEither.left.map(_.getMessage)

  private def s7Overlay(c: Config): Either[String, Map[String, (Double, Double)]] =
    c.asScalaConfig.readRoot[Map[String, Map[String, Double]]] match {
      case Left(error) => Left(error.toString)
      case Right(byProtocol) =>
        val unknown = byProtocol.values.flatMap(_.keys).find(k => k != "ctdi" && k != "dlp")
        unknown match {
          case Some(k) => Left(s"Unknown ctdrl.info value : $k")
          case None    => Right(byProtocol.view.mapValues(m => (m.getOrElse("ctdi", 0.0), m.getOrElse("dlp", 0.0))).toMap)
        }
    }

  @Test
  def s7_unwrappedTypeSwitchOverEntrySet(): Unit = {
    for (text <- Seq("head { ctdi = 75, dlp = 1000.5 }, chest { dlp = 400 }", "a { ctdi = 1.5 }")) {
      val c = parse(text)
      assertEquals(text, s7Original(c), s7Overlay(c))
      assertTrue(s7Original(c).isRight)
    }
    for (text <- Seq("head { cti = 1 }", "head { ctdi = true }", "head { deep { ctdi = 1 } }")) {
      val c = parse(text)
      assertTrue(text, s7Original(c).isLeft)
      assertTrue(text, s7Overlay(c).isLeft)
    }
  }

  @Test
  def s7_overlayFollowsTheCoreConversionRules(): Unit = {
    // `unwrapped` hands the original a Long and a String, which it rejects; getDouble converts both
    val long = parse("head { ctdi = 3000000000 }")
    assertTrue(s7Original(long).isLeft)
    assertEquals(Right(Map("head" -> (3000000000.0, 0.0))), s7Overlay(long))
    val text = parse("""head { ctdi = "1.5" }""")
    assertTrue(s7Original(text).isLeft)
    assertEquals(Right(Map("head" -> (1.5, 0.0))), s7Overlay(text))
  }

  // S8: sconfig ApiExamples (getAnyRef match, obj.asScala)

  @Test
  def s8_getAnyRefMatchAndAsScala(): Unit = {
    val conf = parse("ints { fortyTwo = 42, other = 7 }")
    val d: Int = conf.getAnyRef("ints.fortyTwo") match {
      case x: java.lang.Integer => x
      case x: java.lang.Long    => x.intValue
    }
    assertEquals(Right(d), conf.asScalaConfig.read[Int]("ints.fortyTwo"))
    val asScala = conf.getObject("ints").asScala
    assertEquals(
      Right(asScala.toMap),
      conf.asScalaConfig.read[Map[String, ConfigValue]]("ints")
    )
  }

  @Test
  def s8_overlayDoesNotWrapAroundOnLongs(): Unit = {
    val conf = parse("ints.big = 4294967297")
    val d: Int = conf.getAnyRef("ints.big") match {
      case x: java.lang.Integer => x
      case x: java.lang.Long    => x.intValue
    }
    assertEquals(1, d) // silently wrapped
    assertEquals(
      Left(WrongType("ints.big", "32-bit integer", "out-of-range value 4294967297")),
      conf.asScalaConfig.read[Int]("ints.big")
    )
  }

  // S9: sconfig ConfigTest (NotResolved is thrown for exactly the unresolved keys)

  @Test
  def s9_notResolvedPerKey(): Unit = {
    val partial = parse("good = 42, a = ${x}, c { y = ${y} }")
      .resolve(ConfigResolveOptions.defaults.setAllowUnresolved(true))
    assertEquals(Right(42), partial.asScalaConfig.read[Int]("good"))
    for (k <- Seq("a", "c.y")) {
      intercept[ConfigException.NotResolved](partial.getInt(k))
      assertEquals(Left(Unresolved(k)), partial.asScalaConfig.read[Int](k))
    }
    intercept[ConfigException.NotResolved](partial.getString("c.y"))
    assertEquals(Left(Unresolved("c.y")), partial.asScalaConfig.read[String]("c.y"))
  }

  // S10: branchtalk-io/backend UsersModule

  private def s10Original(appConfig: Option[Config]): (String, Int) =
    (
      appConfig.flatMap(c => Try(c.getString("users.password.algorithm")).toOption).getOrElse("bcrypt"),
      appConfig.flatMap(c => Try(c.getInt("users.password.bcrypt-cost")).toOption).getOrElse(10)
    )
  private def s10Overlay(c: Config): Either[ConfigReadError, (String, Int)] = {
    val api = c.asScalaConfig
    for {
      algorithm <- api.readOption[String]("users.password.algorithm").map(_.getOrElse("bcrypt"))
      cost <- api.readOption[Int]("users.password.bcrypt-cost").map(_.getOrElse(10))
    } yield (algorithm, cost)
  }

  @Test
  def s10_tryToOptionGetOrElse(): Unit =
    for (text <- Seq("", "users.password.algorithm = argon2, users.password.bcrypt-cost = 12", "users.password.bcrypt-cost = null")) {
      val c = parse(text)
      assertEquals(text, Right(s10Original(Some(c))), s10Overlay(c))
    }

  @Test
  def s10_overlayDoesNotHideDefects(): Unit = {
    val wrongType = parse("""users.password.bcrypt-cost = ten""")
    assertEquals(("bcrypt", 10), s10Original(Some(wrongType))) // the typo is swallowed
    assertEquals(Left(WrongType("users.password.bcrypt-cost", "NUMBER", "STRING")), s10Overlay(wrongType))
    val unresolved = parse("users.password.bcrypt-cost = ${COST}")
    assertEquals(("bcrypt", 10), s10Original(Some(unresolved)))
    assertEquals(Left(Unresolved("users.password.bcrypt-cost")), s10Overlay(unresolved))
  }

  // S11: branchtalk-io/backend DiscussionsModule (resolve() inside the Try)

  private def s11Original(c: Config): Int =
    Try(c.resolve().getInt("discussions.url-title-max-length")).getOrElse(100)
  private def s11Overlay(c: Config): Either[ConfigReadError, Int] =
    c.resolve().asScalaConfig.readOption[Int]("discussions.url-title-max-length").map(_.getOrElse(100))

  @Test
  def s11_resolveInsideTry(): Unit =
    for (text <- Seq("discussions.url-title-max-length = 80", "x = 1", "m = 5, discussions.url-title-max-length = ${m}")) {
      val c = parse(text)
      assertEquals(text, Right(s11Original(c)), s11Overlay(c))
    }

  @Test
  def s11_resolveFailuresStayLoud(): Unit = {
    val c = parse("discussions.url-title-max-length = ${nope}")
    assertEquals(100, s11Original(c))
    intercept[ConfigException.UnresolvedSubstitution](s11Overlay(c))
  }

  // S12: ossuminc/riddl-idea-plugin readFromOptionsFromConf

  private def s12Original(config: Config): Seq[String] =
    try config.getObject("from").keySet().iterator().asScala.toSeq
    catch { case _: ConfigException.Missing => Seq() }
  private def s12Overlay(config: Config): Either[ConfigReadError, Seq[String]] =
    config.asScalaConfig.readOption[Map[String, ConfigValue]]("from").map(_.fold(Seq.empty[String])(_.keys.toSeq))

  @Test
  def s12_catchMissingAroundGetObject(): Unit =
    for (text <- Seq("from { a = 1, b = 2 }", "other = 1", "from = null")) {
      val c = parse(text)
      assertEquals(text, Right(s12Original(c).toSet), s12Overlay(c).map(_.toSet))
    }

  @Test
  def s12_nullIsDistinguishableWhenItMatters(): Unit = {
    // `ConfigException.Null extends Missing`, so the original cannot tell the two apart; lookup can
    val c = parse("from = null")
    assertEquals(Seq(), s12Original(c))
    assertEquals(Right(Lookup.Null), c.asScalaConfig.lookup("from"))
    assertEquals(Right(Lookup.Missing), parse("x = 1").asScalaConfig.lookup("from"))
    val wrong = parse("from = 1")
    intercept[ConfigException.WrongType](s12Original(wrong))
    assertEquals(Left(WrongType("from", "OBJECT", "NUMBER")), s12Overlay(wrong))
  }

  // S13: afpma-pro/firecalc implicits (parse + getString inside catch Exception => None)

  private def s13Original(content: String, path: String): Option[String] =
    try Some(ConfigFactory.parseString(content).getString(path))
    catch { case _: Exception => None }
  private def s13Overlay(content: String, path: String): Either[ConfigReadError, Option[String]] =
    ConfigFactory.parseString(content).asScalaConfig.readOption[String](path)

  @Test
  def s13_catchExceptionToNone(): Unit =
    for (content <- Seq("greeting = hello", "other = 1", "greeting = null")) {
      assertEquals(content, Right(s13Original(content, "greeting")), s13Overlay(content, "greeting"))
    }

  @Test
  def s13_overlayDoesNotMistakeBrokenFilesForMissingKeys(): Unit = {
    assertEquals(None, s13Original("greeting = [", "greeting")) // syntax error looks like a missing key
    intercept[ConfigException.Parse](s13Overlay("greeting = [", "greeting"))
    assertEquals(None, s13Original("greeting { a = 1 }", "greeting"))
    assertEquals(Left(WrongType("greeting", "STRING", "OBJECT")), s13Overlay("greeting { a = 1 }", "greeting"))
  }

  // S14 (jodersky/autoset: try/catch around parse and resolve) is not covered by the overlay:
  // it reads values, it does not wrap parsing or resolution.
}
