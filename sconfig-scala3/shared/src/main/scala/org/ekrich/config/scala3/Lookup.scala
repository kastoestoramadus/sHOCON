package org.ekrich.config.scala3

import org.ekrich.config.ConfigValue

/** What is at a path, without collapsing explicit `null` into absence. */
enum Lookup {
  case Missing
  case Null
  case Value(value: ConfigValue)
}
