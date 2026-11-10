package org.ekrich.config.scala3

import org.ekrich.config.Config

extension (config: Config) {

  /** Wraps `config` without copying, parsing or resolving it. */
  def asScalaConfig: ScalaConfig = new ScalaConfig(config)
}

/**
 * A view of a [[org.ekrich.config.Config]] with typed, explicit reads.
 *
 * `asConfig` is the very object that was wrapped, so nothing is lost on the way
 * back: origins, comments, unresolved substitutions and render options are
 * those of the original.
 */
final class ScalaConfig private[scala3] (val asConfig: Config) {

  /**
   * `Left(Missing)` for an absent path and `Left(Null)` for an explicit `null`.
   */
  def read[A](path: String)(using
      reader: ConfigReader[A]
  ): Either[ConfigReadError, A] =
    reader.read(asConfig, path)

  /**
   * `Right(None)` for an absent path and for an explicit `null`; type errors
   * and unresolved values stay `Left`. Use [[lookup]] to tell absence from
   * `null`.
   */
  def readOption[A](path: String)(using
      reader: ConfigReader[A]
  ): Either[ConfigReadError, Option[A]] =
    lookup(path).flatMap {
      case Lookup.Missing | Lookup.Null => Right(None)
      case Lookup.Value(_) => reader.read(asConfig, path).map(Some(_))
    }

  /**
   * Reads the whole config, which is always an object, as `A`. Errors carry
   * paths relative to the root.
   */
  def readRoot[A](using reader: ConfigReader[A]): Either[ConfigReadError, A] = {
    val key = "root"
    reader.read(asConfig.atKey(key), key).left.map(_.rebase(key, ""))
  }

  def lookup(path: String): Either[ConfigReadError, Lookup] =
    ConfigReader.guard(asConfig, path, "any") {
      // hasPath is false for an explicit null
      if (asConfig.hasPath(path)) Lookup.Value(asConfig.getValue(path))
      else if (asConfig.hasPathOrNull(path)) Lookup.Null
      else Lookup.Missing
    }

  /** Same merge as [[org.ekrich.config.Config.withFallback]]. */
  def withFallback(other: ScalaConfig): ScalaConfig =
    new ScalaConfig(asConfig.withFallback(other.asConfig))

  override def equals(other: Any): Boolean = other match {
    case that: ScalaConfig => asConfig == that.asConfig
    case _                 => false
  }
  override def hashCode: Int = asConfig.hashCode
  override def toString: String = asConfig.toString
}
