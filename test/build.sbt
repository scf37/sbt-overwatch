libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.9" % "test"
libraryDependencies += "commons-io" % "commons-io" % "2.5"

val task1 = taskKey[Unit]("task1")
val task2 = taskKey[Unit]("task2")
val task3 = taskKey[Unit]("task3")


val overwatchTest = (project in file("."))
    .settings(
      crossSbtVersions := Seq("0.13.17", "1.5.2"),
      crossScalaVersions := Seq("2.12.8", "2.13.0", "3.0.0"),
      task1 := {
        streams.value.log.info("js changed")
      },
      task2 := {
        streams.value.log.info("css changed")
      },
      task3 := {
        streams.value.log.info("scala changed")
        reStart.toTask("").value
      }

    )

(overwatch in Global) := {
  //start application when we type overwatch
  (reStart in overwatchTest).toTask("").value
  //start watching
  (overwatch in Global).value
  //when we exited, terminate the app
  //for unknown reasons, (reStop in overwatchTest).value does not work
  spray.revolver.Actions.stopAppWithStreams(streams.value, (thisProjectRef in overwatchTest).value)
}

overwatchConfiguration in Global := Map(
  overwatchFilter(baseDirectory.value, "**/*.js") -> (task1 in overwatchTest),
  overwatchFilter(baseDirectory.value / "src/main/resources", "**/*.css") -> (task2 in overwatchTest),
  overwatchFilter(baseDirectory.value , "**/*.scala") -> (task3 in overwatchTest)
)
