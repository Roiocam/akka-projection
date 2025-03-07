import akka.projections.Dependencies
import com.geirsson.CiReleasePlugin
import sbtdynver.DynVerPlugin.autoImport._
import com.lightbend.paradox.projectinfo.ParadoxProjectInfoPluginKeys._
import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin
import com.typesafe.tools.mima.plugin.MimaKeys._
import xerial.sbt.Sonatype.autoImport.sonatypeProfileName

object Common extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = JvmPlugin && CiReleasePlugin

  override def globalSettings =
    Seq(
      organization := "com.lightbend.akka",
      organizationName := "Lightbend Inc.",
      organizationHomepage := Some(url("https://www.lightbend.com/")),
      startYear := Some(2020),
      homepage := Some(url("https://akka.io")),
      // apiURL defined in projectSettings because version.value is not correct here
      scmInfo := Some(
          ScmInfo(url("https://github.com/akka/akka-projection"), "git@github.com:akka/akka-projection.git")),
      developers += Developer(
          "contributors",
          "Contributors",
          "https://gitter.im/akka/dev",
          url("https://github.com/akka/akka-projection/graphs/contributors")),
      licenses := {
        val tagOrBranch =
          if (version.value.endsWith("SNAPSHOT")) "main"
          else "v" + version.value
        Seq(("BUSL-1.1", url(s"https://raw.githubusercontent.com/akka/akka-projection/${tagOrBranch}/LICENSE")))
      },
      description := "Akka Projection.",
      excludeLintKeys += scmInfo,
      excludeLintKeys += mimaPreviousArtifacts,
      excludeLintKeys += testOptions,
      excludeLintKeys += logBuffered)

  override lazy val projectSettings = Seq(
    projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
    crossVersion := CrossVersion.binary,
    crossScalaVersions := Dependencies.Scala2Versions,
    scalaVersion := Dependencies.Scala213,
    javacOptions ++= List("-Xlint:unchecked", "-Xlint:deprecation"),
    Compile / doc / scalacOptions := scalacOptions.value ++ Seq(
        "-doc-title",
        "Akka Projection",
        "-doc-version",
        version.value,
        "-sourcepath",
        (ThisBuild / baseDirectory).value.toString,
        "-doc-source-url", {
          val branch = if (isSnapshot.value) "main" else s"v${version.value}"
          s"https://github.com/akka/akka-projection/tree/${branch}€{FILE_PATH_EXT}#L€{FILE_LINE}"
        })
      ++ {
        if (scalaBinaryVersion.value.startsWith("3")) {
          Seq("-skip-packages:akka.pattern") // different usage in scala3
        } else {
          Seq("-skip-packages", "akka.pattern") // for some reason Scaladoc creates this
        }
      },
    scalafmtOnCompile := System.getenv("CI") != "true",
    autoAPIMappings := true,
    apiURL := Some(url(s"https://doc.akka.io/api/akka-projection/${projectInfoVersion.value}")),
    // show full stack traces and test case durations
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    IntegrationTest / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    // -a Show stack traces and exception class name for AssertionErrors.
    // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
    // -q Suppress stdout for successful tests.
    Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v", "-q"),
    Test / logBuffered := false,
    IntegrationTest / logBuffered := false,
    mimaPreviousArtifacts := {
      moduleName.value match {
        case "akka-projection-r2dbc" =>
          // was released with akka-persistence-r2dbc then moved here
          // FIXME drop once we have a release 1.4.0 release out
          Set(organization.value %% moduleName.value % "1.0.1")
        case "akka-projection-grpc" =>
          // FIXME drop once we have 1.4.0 out
          Set.empty
        case "akka-projection-kafka" if scalaBinaryVersion.value == "3" =>
          // FIXME drop once we have 1.4.0 out
          Set.empty
        case name if name.endsWith("-tests") => Set.empty
        case _ =>
          Set(
            organization.value %% moduleName.value % previousStableVersion.value
              .getOrElse(throw new Error("Unable to determine previous version")))

      }
    },
    sonatypeProfileName := "com.lightbend")

}
