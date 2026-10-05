package org.ekrich.config.scala3

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration
import scala.util.boundary
import scala.util.boundary.break

import org.ekrich.config.Config
import org.ekrich.config.ConfigException
import org.ekrich.config.ConfigMemorySize
import org.ekrich.config.ConfigUtil
import org.ekrich.config.ConfigValue

import ConfigReadError.*

/**
 * Reads a value of type `A` at a path of a [[org.ekrich.config.Config]].
 *
 * The instances for scalars delegate to the `Config` getter of the same type,
 * so they convert exactly as the core does (a string `"42"` reads as an `Int`,
 * a number reads as a `String`). An implementation must not catch exceptions
 * other than the ones it maps itself.
 */
trait ConfigReader[A] {
  def read(config: Config, path: String): Either[ConfigReadError, A]
}

object ConfigReader {

  // the key a list element or map value is placed under to be read like any other path
  private val Key = "v"

  /**
   * Runs `body`, which reads `path`, and maps the four exceptions a getter
   * throws.
   */
  private[scala3] def guard[A](config: Config, path: String, expected: String)(
      body: => A
  ): Either[ConfigReadError, A] =
    try Right(body)
    catch {
      // Null extends Missing, so it has to come first. The exception does not say which
      // path is null: below a null parent nothing is set at `path`.
      case _: ConfigException.Null =>
        Left(if (isNullAt(config, path)) Null(path) else Missing(path))
      case _: ConfigException.Missing     => Left(Missing(path))
      case _: ConfigException.NotResolved => Left(Unresolved(path))
      case _: ConfigException.WrongType   =>
        try
          Left(WrongType(path, expected, config.getValue(path).valueType.name))
        // a parent of the path is not an object, so there is nothing at the path
        catch { case _: ConfigException.WrongType => Left(Missing(path)) }
    }

  private def isNullAt(config: Config, path: String): Boolean =
    try config.getIsNull(path)
    catch { case _: ConfigException => false }

  private def leaf[A](expected: String)(
      get: (Config, String) => A
  ): ConfigReader[A] =
    (config, path) => guard(config, path, expected)(get(config, path))

  given ConfigReader[String] = leaf("STRING")(_.getString(_))
  given ConfigReader[Boolean] = leaf("BOOLEAN")(_.getBoolean(_))
  given ConfigReader[Long] = leaf("NUMBER")(_.getLong(_))
  given ConfigReader[Double] = leaf("NUMBER")(_.getDouble(_))
  given ConfigReader[java.time.Duration] =
    leaf("STRING or NUMBER")(_.getDuration(_))
  given ConfigReader[ConfigMemorySize] =
    leaf("STRING or NUMBER")(_.getMemorySize(_))
  given ConfigReader[ConfigValue] = leaf("any")(_.getValue(_))

  /**
   * Nanosecond precision; the core clamps a duration beyond about 292 years.
   */
  given ConfigReader[FiniteDuration] =
    leaf("STRING or NUMBER") { (c, p) =>
      FiniteDuration(
        c.getDuration(p, TimeUnit.NANOSECONDS),
        TimeUnit.NANOSECONDS
      )
    }

  given ConfigReader[Int] = (config, path) =>
    guard(config, path, "NUMBER")(config.getInt(path)) match {
      // the value is a number, but does not fit
      case Left(WrongType(p, "NUMBER", "NUMBER")) =>
        Left(
          WrongType(
            p,
            "32-bit integer",
            "out-of-range value " + config.getValue(p).unwrapped
          )
        )
      case other => other
    }

  given [A](using reader: ConfigReader[A]): ConfigReader[List[A]] =
    (config, path) =>
      guard(config, path, "LIST")(config.getList(path)).flatMap { list =>
        boundary {
          val out = List.newBuilder[A]
          var i = 0
          val it = list.iterator()
          while (it.hasNext) {
            reader.read(it.next().atKey(Key), Key) match {
              case Right(a) => out += a
              case Left(e)  => break(Left(e.rebase(Key, s"$path[$i]")))
            }
            i += 1
          }
          Right(out.result())
        }
      }

  given [A: ConfigReader]: ConfigReader[Set[A]] =
    (config, path) =>
      summon[ConfigReader[List[A]]].read(config, path).map(_.toSet)

  given [A](using reader: ConfigReader[A]): ConfigReader[Map[String, A]] =
    (config, path) =>
      guard(config, path, "OBJECT")(config.getObject(path)).flatMap { obj =>
        boundary {
          val out = Map.newBuilder[String, A]
          val it = obj.keySet().iterator()
          while (it.hasNext) {
            val key = it.next()
            reader.read(obj.get(key).atKey(Key), Key) match {
              case Right(a) => out += key -> a
              case Left(e)  =>
                break(Left(e.rebase(Key, ConfigUtil.joinPath(path, key))))
            }
          }
          Right(out.result())
        }
      }
}
