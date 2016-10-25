# sbt-overwatch plugin
![Build status](https://travis-ci.org/scf37/sbt-overwatch.svg?branch=master)

This plugin is smarter replacement for sbt watch feature (`~` command). 

Features:
- ability to watch over multiple directories
- ability to execute different tasks for different directories


##Usage

```
overwatchConfiguration in Global := Map(
        overwatchFilter(baseDirectory.value, "**/*.scala")
                  .exclude("**/target/**") -> reStart,
        overwatchFilter(baseDirectory.value, "**/*.js") -> gulp,
        overwatchFilter(baseDirectory.value / "web", "**/*.scala.html") -> (TwirlKeys.compileTemplates in Compile)
)

```

See test/build.sbt for complete example including sbt-revolver integration.

