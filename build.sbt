sbtPlugin := true

name := "sbt-cpp"

organization := "org.seacourt.build"

version := "0.1.2-SNAPSHOT"

publishMavenStyle := false

publishTo := Some(Resolver.file("file", new File("./releases")))

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.0.1",
  "com.sleepycat" % "je" % "4.0.92",
  "org.scalatest" %% "scalatest" % "1.9.1" % "test"
)

scalacOptions ++= Seq("-deprecation")

ScriptedPlugin.scriptedSettings

scriptedLaunchOpts := scriptedLaunchOpts.value ++ Seq(
  "-XX:MaxPermSize=256M", 
  "-Dplugin.version=" + version.value
)

scriptedBufferLog := false

