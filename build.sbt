import com.typesafe.sbt.packager.docker._

enablePlugins(JavaAppPackaging, DockerPlugin, ScoverageSbtPlugin)

organization := "org.renci"

name := "cam-kp-api"

version := "0.1"

licenses := Seq("MIT license" -> url("https://opensource.org/licenses/MIT"))

scalaVersion := "2.13.4"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javaOptions += "-Xmx8G"

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

publishArtifact in (Compile, packageDoc) := false

configs(IntegrationTest)
Defaults.itSettings
coverageEnabled := false
IntegrationTest / parallelExecution := false

coverageExcludedPackages := "<empty>;org\\.renci\\.cam\\.domain\\..*;org\\.renci\\.cam\\.Server.*;org\\.renci\\.cam\\.AppConfig.*;org\\.renci\\.cam\\.SPARQLQueryExecutor.*"

val zioVersion = "1.0.4-2"
val zioConfigVersion = "1.0.3"
val tapirVersion = "0.16.16"
val http4sVersion = "0.21.13"
val circeVersion = "0.13.0"
val logbackVersion = "1.2.3"

javaOptions in reStart += "-Xmx16G"

libraryDependencies ++= {
  Seq(
    "dev.zio"                     %% "zio"                              % zioVersion,
    "dev.zio"                     %% "zio-interop-cats"                 % "2.2.0.1",
    "dev.zio"                     %% "zio-config"                       % zioConfigVersion,
    "dev.zio"                     %% "zio-config-magnolia"              % zioConfigVersion,
    "dev.zio"                     %% "zio-config-typesafe"              % zioConfigVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-core"                       % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-zio"                        % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-zio-http4s-server"          % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-json-circe"                 % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs"               % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-openapi-circe-yaml"         % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-http4s"          % tapirVersion,
    "org.http4s"                  %% "http4s-blaze-server"              % http4sVersion,
    "org.http4s"                  %% "http4s-dsl"                       % http4sVersion,
    "org.http4s"                  %% "http4s-blaze-client"              % http4sVersion,
    "org.http4s"                  %% "http4s-circe"                     % http4sVersion,
    "org.apache.jena"              % "apache-jena-libs"                 % "3.16.0",
    "org.phenoscape"              %% "sparql-utils"                     % "1.3",
    "org.apache.commons"           % "commons-text"                     % "1.9",
    "org.apache.commons"           % "commons-csv"                      % "1.8",
    "io.circe"                    %% "circe-core"                       % circeVersion,
    "io.circe"                    %% "circe-generic"                    % circeVersion,
    "io.circe"                    %% "circe-parser"                     % circeVersion,
    "io.circe"                    %% "circe-yaml"                       % circeVersion,
    "dev.zio"                     %% "zio-test"                         % zioVersion % "it,test",
    "dev.zio"                     %% "zio-test-sbt"                     % zioVersion % "it,test",
    "com.dimafeng"                %% "testcontainers-scala-scalatest"   % "0.39.3" % "it,test",
    "com.google.guava"             % "guava"                            % "30.0-jre",
    "ch.qos.logback"               % "logback-classic"                  % logbackVersion,
    "com.typesafe.scala-logging"  %% "scala-logging"                    % "3.9.2"
  )
}

dockerBaseImage := "openjdk:14-alpine"
daemonUser in Docker := "camkpapi"
dockerExposedPorts += 8080
dockerApiVersion := Some(DockerApiVersion(1, 40))
dockerChmodType := DockerChmodType.UserGroupWriteExecute
dockerRepository := Some("renciorg")
dockerCommands := dockerCommands.value.flatMap {
  case cmd @ Cmd("EXPOSE", _) => List(Cmd("RUN", "apk update && apk add bash curl"), cmd)
  case other                  => List(other)
}
