# sbt-overwatch plugin
[![Build status](https://travis-ci.org/scf37/sbt-overwatch.svg?branch=master)](https://travis-ci.org/scf37/sbt-overwatch)

This plugin is smarter replacement for sbt watch feature (`~` command). 

Features:
- ability to watch over multiple directories
- ability to execute different tasks for different directories


## Usage
Add this to `project/plugins.sbt`:
```
resolvers += Resolver.url("plugins", url("https://dl.bintray.com/scf37/sbt-plugins"))(Resolver.ivyStylePatterns)
resolvers += "Scf37" at "https://dl.bintray.com/scf37/maven/"
addSbtPlugin("me.scf37.overwatch" % "sbt-overwatch" % "1.0.8")
```

Configure plugin and type `overwatch`:
```
> overwatch
[info] Waiting for changes on /home/scf37/dev/sbt-overwatch/test includes: **/*.js
[info] Waiting for changes on /home/scf37/dev/sbt-overwatch/test/src/main/resources includes: **/*.css
[info] Waiting for changes on /home/scf37/dev/sbt-overwatch/test includes: **/*.scala
[info] Waiting for changes... (press enter to interrupt)
```

Plugin configuration constists of `fileset` -> `sbt task` pairs. `overwatchFilter` supports 
[jdk globs](https://docs.oracle.com/javase/7/docs/api/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String)) or simple `Path => Boolean` closures.

```
overwatchConfiguration in Global := Map(
  overwatchFilter(baseDirectory.value, "**/*.js") -> (task1 in overwatchTest),
  overwatchFilter(baseDirectory.value / "src/main/resources", "**/*.css") -> (task2 in overwatchTest),
  overwatchFilter(baseDirectory.value , "**/*.scala") -> (task3 in overwatchTest)
)
```

See [test/build.sbt](https://github.com/scf37/sbt-overwatch/blob/master/test/build.sbt) for complete example including sbt-revolver integration.

