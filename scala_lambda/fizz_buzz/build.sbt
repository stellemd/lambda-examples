import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.galacticfog",
      scalaVersion := "2.12.1",
      version      := "0.1"
    )),
    name := "fizzbuzz",

    mainClass in (Compile, run) := Some("com.galacticfog.FizzBuzz"),

    libraryDependencies += scalaTest % Test,
    libraryDependencies += "com.googlecode.json-simple" % "json-simple" % "1.1.1",
    libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.25",
    libraryDependencies += "joda-time" % "joda-time" % "2.9.9"

)

enablePlugins(JavaAppPackaging)

import com.typesafe.sbt.packager.docker._

dockerBaseImage := "java:8-jre-alpine"

dockerCommands := dockerCommands.value.flatMap {
    case cmd@Cmd("FROM",_) => List(
        cmd,
        Cmd("RUN", "apk add --update bash && rm -rf /var/cache/apk/*")
        )
        case other => List(other)
}

maintainer in Docker := "Brad Futch <brad@galacticfog.com>"

dockerUpdateLatest := true

dockerRepository := Some("galacticfog")

