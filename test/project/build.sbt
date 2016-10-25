lazy val overwatch = RootProject(file("../../plugin"))
lazy val root = (project in file(".")).dependsOn(overwatch)


