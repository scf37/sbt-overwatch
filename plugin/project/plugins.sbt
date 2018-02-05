addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.1")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.6")

resolvers += Resolver.url("plugins", url("https://dl.bintray.com/scf37/sbt-plugins"))(Resolver.ivyStylePatterns)
addSbtPlugin("me.scf37.buildprops" % "sbt-build-properties" % "1.0.7")