package org.ekrich.config.scala3

import org.ekrich.config.ConfigValue

/** The lossless result of looking up a path. */
enum Lookup {
  case Missing
  case Null
  case Value(value: ConfigValue)
}
