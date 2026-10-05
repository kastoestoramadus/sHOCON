# sconfig-scala3

An opt-in Scala 3 layer over the unchanged `org.ekrich.config.Config`. It adds typed reads that return
`Either`/`Option`, immutable Scala collections and `-Yexplicit-nulls` friendly signatures. It is a
thin wrapper: parsing, resolution, fallback and rendering stay in `sconfig`, and the existing API is
not changed or deprecated.

```scala
libraryDependencies += "org.ekrich" %% "sconfig-scala3" % "<version>" // Scala 3.9.0, JVM, Scala.js, Native
```

On Scala.js and Scala Native add `sjavatime` as for `sconfig` itself (see [SCALA_NATIVE.md](../docs/SCALA_NATIVE.md)).

## Use

```scala
import org.ekrich.config.ConfigFactory
import org.ekrich.config.scala3.*

val api = ConfigFactory.load().asScalaConfig

val workers:  Either[ConfigReadError, Int]            = api.read[Int]("service.workers")
val endpoint: Either[ConfigReadError, Option[String]] = api.readOption[String]("service.endpoint")
val hosts:    Either[ConfigReadError, List[String]]   = api.read[List[String]]("service.hosts")
val limits:   Either[ConfigReadError, Map[String, Int]] = api.read[Map[String, Int]]("service.limits")

val existing: org.ekrich.config.Config = api.asConfig // the same object, not a copy
```

Readers exist for `String`, `Boolean`, `Int`, `Long`, `Double`, `java.time.Duration`,
`scala.concurrent.duration.FiniteDuration`, `ConfigMemorySize`, `ConfigValue`, and `List[A]`,
`Set[A]`, `Map[String, A]` of any of them. `Option` is deliberately not a field type: use
`readOption`. Write your own with `ConfigReader[A]`.

### Absence, `null` and errors

| What is at the path | `read` | `readOption` | `lookup` |
|---|---|---|---|
| a value of the right type | `Right(a)` | `Right(Some(a))` | `Right(Lookup.Value(v))` |
| nothing (or a parent that is not an object) | `Left(Missing(path))` | `Right(None)` | `Right(Lookup.Missing)` |
| an explicit HOCON `null` | `Left(Null(path))` | `Right(None)` | `Right(Lookup.Null)` |
| a value of another type | `Left(WrongType(path, expected, actual))` | same | `Right(Lookup.Value(v))` |
| a substitution that was not resolved | `Left(Unresolved(path))` | same | same |

`readOption` treats absence and `null` alike because that is what callers of an optional setting
usually mean; `lookup` is the lossless operation. Inside a list or map the path names the element
(`hosts[2]`, `limits.cpu`). A path below an explicit `null` is `Missing`, as nothing is set there.

Only these four conditions become a `ConfigReadError`. Every other exception propagates, so a
malformed path (`BadPath`) or an unparsable duration (`BadValue`) is not hidden, and neither is an
exception from your own `ConfigReader`. `ConfigReadError.Other` is for readers you write.

Values convert as in the core, because the readers call the core getters: `"42"` reads as an `Int`,
`1.9` reads as `1`, a number reads as a `String`.

## Migrating one call site at a time

```scala
// before
val labels =
  if (raw.hasPath("hosts.labels")) raw.getStringList("hosts.labels").asScala.toSet
  else Set.empty[String]
val cost = Try(raw.getInt("users.password.bcrypt-cost")).toOption.getOrElse(10) // a typo falls back to 10

// after
val labels = raw.asScalaConfig.readOption[Set[String]]("hosts.labels").map(_.getOrElse(Set.empty))
val cost   = raw.asScalaConfig.readOption[Int]("users.password.bcrypt-cost").map(_.getOrElse(10)) // a typo is Left(WrongType)

// the rest of the program, and libraries that take a Config, are untouched
someLibrary.start(raw.asScalaConfig.asConfig)
```

`asScalaConfig` and `asConfig` do not parse, render or resolve: origins, comments and unresolved
substitutions are those of the original object. `withFallback` on `ScalaConfig` is the core's.

## What it costs

No zero-cost claim is made.

- `asScalaConfig` allocates one small wrapper per call (`ScalaConfig` is not a value class).
- Every read returns an `Either`, and a scalar is boxed inside it.
- `readOption` looks the path up first (`lookup`) and then reads it, so it resolves the path more than once.
- Reads of a missing path or a wrong type are caught from the core's exceptions, with their stack
  traces; `readOption` and `lookup` avoid that for absence and `null`.
- `List`, `Set` and `Map` copy into new immutable collections. Each element is read through
  `ConfigValue.atKey`, which allocates a one-key config per element; `getIntList` does not.
- `Map` is the default immutable `Map`: key order is kept only for up to four entries.
- `FiniteDuration` has nanosecond resolution and throws `ArithmeticException` beyond about 292 years.

## Limits

- Scala 3.9.0 only, built with `-Yexplicit-nulls`; JVM, Scala.js and Scala Native are tested.
  Anything the core does not support on a platform is not supported here.
- Reads only: parsing and `resolve()` still throw `ConfigException` as in the core.
- The root of a config has no path; use `readRoot`.
- Errors carry a path, not the origin (file and line) of the value; use `lookup` and
  `ConfigValue.origin` when you need it.
- No `derives`/`Mirror` support yet.
