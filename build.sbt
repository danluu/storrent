name  :=  "storrent"

version := "0.1"


scalaVersion := "2.10.0"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++=
    "com.typesafe.akka" %% "akka-actor" % "2.1.1" ::
    "com.typesafe.akka" %% "akka-testkit" % "2.1.1" % "test" ::
    "com.typesafe.akka" %% "akka-agent" % "2.1.1" ::
    "org.scalatest" %% "scalatest" % "2.0.M5b" % "test" ::
    "org.scalaj" %% "scalaj-http" % "0.3.6"  ::
    "commons-io" % "commons-io" % "2.1" :: 
    Nil

