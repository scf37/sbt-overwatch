val task1 = taskKey[Unit]("task1")
val task2 = taskKey[Unit]("task2")
val task3 = taskKey[Unit]("task3")


val overwatchTest = (project in file("."))
    .settings(
      scalaVersion := "2.11.8",
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
  (reStart in overwatchTest).toTask("").value
  (overwatch in Global).value
  //for unknown reasons, (reStop in overwatchTest).value does not work
  spray.revolver.Actions.stopAppWithStreams(streams.value, (thisProjectRef in overwatchTest).value)
}

overwatchConfiguration in Global := Map(
  overwatchFilter(baseDirectory.value, "**/*.js") -> (task1 in overwatchTest),
  overwatchFilter(baseDirectory.value / "src/main/resources", "**/*.css") -> (task2 in overwatchTest),
  overwatchFilter(baseDirectory.value , "**/*.scala") -> (task3 in overwatchTest)
)
