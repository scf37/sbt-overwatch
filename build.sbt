lazy val overwatch = (project in file("."))
    .settings(

    name := "sbt-overwatch",
    organization := "me.scf37.overwatch",
    sbtPlugin := true,

    releaseTagComment := s"[ci skip]Releasing ${(version in ThisBuild).value}",
    releaseCommitMessage := s"[ci skip]Setting version to ${(version in ThisBuild).value}",

//    bintrayOmitLicense := true,
    bintrayVcsUrl := Some("git@github.com:scf37/sbt-overwatch.git"),
    bintrayRepository := "sbt-plugins",
    publishMavenStyle := false,
    bintrayOrganization := None,
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),

    resourceGenerators in Compile <+= buildProperties

)



