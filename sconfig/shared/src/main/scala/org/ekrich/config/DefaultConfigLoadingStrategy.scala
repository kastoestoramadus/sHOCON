package org.ekrich.config

/**
 * Default config loading strategy. Able to load resource, file or URL. Behavior
 * may be altered by defining one of VM properties `config.resource`,
 * `config.file` or `config.url`
 */
class DefaultConfigLoadingStrategy extends ConfigLoadingStrategy {
  override def parseApplicationConfig(
      parseOptions: ConfigParseOptions
  ): Config = {
    if (parseOptions.getClassLoader == null)
      throw new ConfigException.BugOrBroken(
        "ClassLoader should have been set here; bug in ConfigFactory. " + "(You can probably work around this bug by passing in a class loader or calling currentThread().setContextClassLoader() though.)"
      )
    ConfigFactory
      .parseApplicationReplacement(parseOptions)
      .getOrElse(
        ConfigFactory.parseResourcesAnySyntax("application", parseOptions)
      )
  }
}
