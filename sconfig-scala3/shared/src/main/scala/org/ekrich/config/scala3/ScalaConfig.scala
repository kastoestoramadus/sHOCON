package org.ekrich.config.scala3

import org.ekrich.config.Config

extension (config: Config) {
  def asScalaConfig: ScalaConfig = ???
}

final class ScalaConfig private[scala3] (val asConfig: Config) {
  def read[A](path: String)(using ConfigReader[A]): Either[ConfigReadError, A] = ???
  def readOption[A](path: String)(using
      ConfigReader[A]
  ): Either[ConfigReadError, Option[A]] = ???
  def readRoot[A](using ConfigReader[A]): Either[ConfigReadError, A] = ???
  def lookup(path: String): Either[ConfigReadError, Lookup] = ???
  def withFallback(other: ScalaConfig): ScalaConfig = ???
}
