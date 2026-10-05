package org.ekrich.config.scala3

/**
 * Why a typed read of a [[org.ekrich.config.Config]] path failed.
 *
 * Only the four exceptions a getter throws for a bad value are mapped
 * (`Missing`, `Null`, `WrongType`, `Unresolved`). Every other exception, such
 * as `ConfigException.BadValue` or `BadPath`, propagates. `Other` is for
 * [[ConfigReader]]s written by users.
 */
enum ConfigReadError {

  /** Nothing is set at `path`, or a parent of `path` is not an object. */
  case Missing(path: String)

  /** `path` is explicitly set to HOCON `null`. */
  case Null(path: String)

  /**
   * `expected` and `actual` are `ConfigValueType` names unless a reader says
   * otherwise.
   */
  case WrongType(path: String, expected: String, actual: String)

  /** The value at `path` still contains a substitution. */
  case Unresolved(path: String)
  case Other(path: String, cause: Throwable)

  def path: String

  /** Replaces the leading path element `from` by `to` (`to` may be empty). */
  private[scala3] def rebase(from: String, to: String): ConfigReadError = {
    def move(p: String): String =
      if (p == from) to
      else if (p.startsWith(from + ".")) {
        val rest = p.substring(from.length + 1)
        if (to.isEmpty) rest else to + "." + rest
      } else if (p.startsWith(from + "[")) to + p.substring(from.length)
      else p
    this match {
      case e: ConfigReadError.Missing    => e.copy(path = move(e.path))
      case e: ConfigReadError.Null       => e.copy(path = move(e.path))
      case e: ConfigReadError.WrongType  => e.copy(path = move(e.path))
      case e: ConfigReadError.Unresolved => e.copy(path = move(e.path))
      case e: ConfigReadError.Other      => e.copy(path = move(e.path))
    }
  }
}
