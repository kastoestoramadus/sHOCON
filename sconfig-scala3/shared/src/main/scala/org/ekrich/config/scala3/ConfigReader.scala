package org.ekrich.config.scala3

import scala.concurrent.duration.FiniteDuration

import org.ekrich.config.Config
import org.ekrich.config.ConfigMemorySize
import org.ekrich.config.ConfigValue

/** Reads a value of type `A` at a path of a [[org.ekrich.config.Config]]. */
trait ConfigReader[A] {
  def read(config: Config, path: String): Either[ConfigReadError, A]
}

object ConfigReader {
  given ConfigReader[String] = ???
  given ConfigReader[Boolean] = ???
  given ConfigReader[Int] = ???
  given ConfigReader[Long] = ???
  given ConfigReader[Double] = ???
  given ConfigReader[java.time.Duration] = ???
  given ConfigReader[FiniteDuration] = ???
  given ConfigReader[ConfigMemorySize] = ???
  given ConfigReader[ConfigValue] = ???
  given [A: ConfigReader]: ConfigReader[List[A]] = ???
  given [A: ConfigReader]: ConfigReader[Set[A]] = ???
  given [A: ConfigReader]: ConfigReader[Map[String, A]] = ???
}
