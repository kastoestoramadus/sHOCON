package org.ekrich.config.scala3

/** Why a typed read of a [[org.ekrich.config.Config]] path failed. */
enum ConfigReadError {
  case Missing(path: String)
  case Null(path: String)
  case WrongType(path: String, expected: String, actual: String)
  case Unresolved(path: String)
  case Other(path: String, cause: Throwable)

  def path: String
}
