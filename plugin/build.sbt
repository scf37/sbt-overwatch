lazy val overwatch = (project in file("."))
    .settings(
    name := "sbt-overwatch",
    sbtPlugin := true,

    libraryDependencies += "me.scf37" %% "filewatch" % "1.0.0",

    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),

    Compile / resourceGenerators += buildProperties,
    publishSettings
)

lazy val publishSettings = Seq(
      organization := "me.scf37",
      description := "Watch project files and trigger sbt tasks on changes",
      Compile / doc / sources := Seq.empty,
      scmInfo := Some(
            ScmInfo(
                  url("https://github.com/scf37/sbt-overwatch"),
                  "git@github.com:scf37/sbt-overwatch.git"
            )
      ),
      licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
      homepage := Some(url("https://github.com/scf37/sbt-overwatch")),
      developers := List(
            Developer("scf37", "Sergey Alaev", "scf370@gmail.com", url("https://github.com/scf37")),
      )
)




