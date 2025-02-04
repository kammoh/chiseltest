// SPDX-License-Identifier: Apache-2.0

import mill._
import mill.scalalib._
import mill.scalalib.scalafmt._
import mill.scalalib.publish._
import mill.util.Util
import coursier.maven.MavenRepository

import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`
import mill.contrib.bloop.Bloop

import $ivy.`com.lihaoyi::mill-contrib-buildinfo:`
import mill.contrib.buildinfo.BuildInfo

import $ivy.`io.chris-kipp::mill-ci-release::0.3.0`
import io.kipp.mill.ci.release.CiReleaseModule
import io.kipp.mill.ci.release.SonatypeHost

import java.io.IOException
import scala.util.matching.Regex

object firrtl2 extends AnyScalaModule with SbtModule with BuildInfo with CiReleaseModule {
  override def artifactName = "firrtl2"

  override def ivyDeps = T {
    Agg(
      ivyDep("scopt"),
      ivyDep("os-lib"),
      ivyDep("json4s-native"),
      ivyDep("commons-text"),
      ivyDep("scala-parallel-collections"),
      ivy"org.antlr:antlr4-runtime:$antlr4Version"
    )
  }

  def generatedAntlr4Source = T.sources {
    antlr4Path().path match {
      case f if f.last == "antlr4.jar" =>
        os.proc(
          "java",
          "-jar",
          f.toString,
          "-o",
          T.ctx().dest.toString,
          "-lib",
          antlrSource().path.toString,
          "-package",
          "firrtl2.antlr",
          "-listener",
          "-visitor",
          antlrSource().path.toString
        ).call()
      case _ =>
        os.proc(
          antlr4Path().path.toString,
          "-o",
          T.ctx().dest.toString,
          "-lib",
          antlrSource().path.toString,
          "-package",
          "firrtl2.antlr",
          "-listener",
          "-visitor",
          antlrSource().path.toString
        ).call()
    }

    T.ctx().dest
  }

  override def generatedSources = T {
    super.generatedSources() ++ generatedAntlr4Source()
  }

// compare version, 1 for (a > b), 0 for (a == b), -1 for (a < b)
  def versionCompare(a: String, b: String) = {
    def nums(s: String) = s.split("\\.").map(_.toInt)
    val pairs = nums(a).zipAll(nums(b), 0, 0).toList
    def go(ps: List[(Int, Int)]): Int = ps match {
      case Nil => 0
      case (a, b) :: t =>
        if (a > b) 1 else if (a < b) -1 else go(t)
    }
    go(pairs)
  }

  /* antlr4 */
  def antlr4Version = "4.13.2"

  def antlrSource = T.source {
    millSourcePath / "src" / "main" / "antlr4" / "FIRRTL.g4"
  }

  val checkSystemAntlr4Version: Boolean = true

  def antlr4Path = T.persistent {
    // Linux distro package antlr4 as antlr4, while brew package as antlr
    PathRef(Seq("antlr4", "antlr").flatMap { f =>
      try {
        // pattern to extract version from antlr4/antlr version output
        val versionPattern: Regex = """(?s).*(\d+\.\d+\.\d+).*""".r
        // get version from antlr4/antlr version output
        val systemAntlr4Version = os.proc(f).call(check = false).out.text().trim match {
          case versionPattern(v) => v
          case _                 => "0.0.0"
        }
        val systemAntlr4Path = os.Path(os.proc("bash", "-c", s"command -v $f").call().out.text().trim)
        if (checkSystemAntlr4Version)
          // Perform strict version checking
          // check if system antlr4 version is the same as the one we want
          if (versionCompare(systemAntlr4Version, antlr4Version) == 0)
            Some(systemAntlr4Path)
          else
            None
        else
        // Perform a cursory version check, avoid using antlr2
        // check if system antlr4 version is greater than 4.0.0
        if (versionCompare(systemAntlr4Version, "4.0.0") >= 0)
          Some(systemAntlr4Path)
        else
          None
      } catch {
        case _: IOException =>
          None
      }
    }.headOption match {
      case Some(bin) =>
        println(s"Use system antlr4: $bin")
        bin
      case None =>
        println("Download antlr4 from Internet")
        if (!os.isFile(T.ctx().dest / "antlr4.jar"))
          Util.download(s"https://www.antlr.org/download/antlr-$antlr4Version-complete.jar", os.rel / "antlr4.jar")
        T.ctx().dest / "antlr4.jar"
    })
  }

  object test extends AnyScalaModule with SbtTests with TestModule.ScalaTest {
    override def ivyDeps = Agg(
      ivyDep("scalatest"),
      ivyDep("scalacheck")
    )
  }

  override def buildInfoPackageName = "firrtl2"

  override def buildInfoMembers = Seq(
    BuildInfo.Value("name", artifactName()),
    BuildInfo.Value("version", publishVersion()),
    BuildInfo.Value("scalaVersion", scalaVersion())
  )

  override def pomSettings = T {
    PomSettings(
      description = artifactName(),
      organization = "xyz.kamyar",
      url = "https://github.com/ucb-bar/firrtl2",
      licenses = Seq(License.`BSD-3-Clause`),
      versionControl = VersionControl.github("xyz.kamyar", "firrtl2"),
      developers = Seq(
        Developer("ducky64", "Richard Lin", "https://aspire.eecs.berkeley.edu/author/rlin/")
      )
    )
  }
}


object chiseltest extends AnyScalaModule with SbtModule with BuildInfo with CiReleaseModule {
  override def millSourcePath = super.millSourcePath / os.up

  override def scalacOptions = T {
    super.scalacOptions() ++ Seq(
      "-language:reflectiveCalls",
      "-Ymacro-annotations",
      // do not warn about firrtl imports, once the firrtl repo is removed, we will need to import the code
      "-Wconf:cat=deprecation&msg=Importing from firrtl is deprecated:s",
      // do not warn about firrtl deprecations
      "-Wconf:cat=deprecation&msg=will not be supported as part of the migration to the MLIR-based FIRRTL Compiler:s"
    )
  }

  override def javacOptions = T {
    super.javacOptions()
  }

  override def ivyDeps = Agg(
    ivyDep("chisel"),
    ivyDep("jna"),
    ivyDep("scalatest")
  )

  override def scalacPluginIvyDeps = Agg(ivyDep("chisel-plugin"))

  object test extends AnyScalaModule with SbtTests with TestModule.ScalaTest {

    override def scalacPluginIvyDeps = Agg(ivyDep("chisel-plugin"))

    override def ivyDeps = Agg(ivyDep("chisel"))
  }

  override def moduleDeps = Seq(firrtl2)

  override def artifactName = "chiseltest"

  override def buildInfoPackageName = "chiseltest"

  override def buildInfoMembers = Seq(
    BuildInfo.Value("name", artifactName()),
    BuildInfo.Value("version", publishVersion()),
    BuildInfo.Value("scalaVersion", scalaVersion()),
    BuildInfo.Value("chiselVersion", getVersion("chisel")),
  )

  override def pomSettings = T {
    PomSettings(
      description = artifactName(),
      organization = "xyz.kamyar",
      url = "https://github.com/kammoh/chiseltest",
      licenses = Seq(License.`BSD-3-Clause`),
      versionControl = VersionControl.github("kammoh", "chiseltest"),
      developers = Seq(
        Developer("ducky64", "Richard Lin", "https://aspire.eecs.berkeley.edu/author/rlin/")
      )
    )
  }

  override def sonatypeUri = "https://oss.sonatype.org/service/local"
  override def sonatypeSnapshotUri = "https://oss.sonatype.org/content/repositories/snapshots"
}

trait AnyScalaModule extends ScalaModule {
  override def scalaVersion = "2.13.15"

  override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/releases"),
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots"),
      MavenRepository("https://s01.oss.sonatype.org/content/repositories/releases"),
      MavenRepository("https://s01.oss.sonatype.org/content/repositories/snapshots"),
      MavenRepository(s"file://${os.home}/.m2/repository")
    )
  }

  override def scalacOptions = T {
    super.scalacOptions() ++ Seq(
      "-deprecation",
      "-feature",
      "-Xcheckinit"
    )
  }

  val defaultVersions = Map(
    "chisel" -> ("org.chipsalliance", "7.0.0-M2+317-62ca9bdc-SNAPSHOT"),
    "chisel-plugin" -> ("org.chipsalliance:::", "$chisel"),
    "scalatest" -> ("org.scalatest", "3.2.19"),
    "scalacheck" -> ("org.scalatestplus::scalacheck-1-17", "3.2.17.0"),
    "scopt" -> ("com.github.scopt", "4.1.0"),
    "os-lib" -> ("com.lihaoyi", "0.11.3"),
    "json4s-native" -> ("org.json4s", "4.1.0-M8"),
    "commons-text" -> ("org.apache.commons:", "1.13.0"),
    "scala-parallel-collections" -> ("org.scala-lang.modules", "1.1.0"),
    "jna" -> ("net.java.dev.jna:", "5.16.0")
  )

  @annotation.tailrec
  final def getOrgAndVersion(dep: String, defaultOrg: Option[String] = None): (String, String) = {
    val (o, v) = defaultVersions(dep)
    val org = defaultOrg.getOrElse(o)
    val version = sys.env.getOrElse(dep + "Version", v)
    if (version.startsWith("$"))
      getOrgAndVersion(version.drop(1), Some(org))
    else
      (org, version)
  }

  def getVersion(dep: String): String = getOrgAndVersion(dep)._2

  def ivyDep(dep: String) = {
    val (org, version) = getOrgAndVersion(dep)
    ivy"$org${if (!org.contains(":")) "::" + dep else if (org.endsWith(":")) dep else ""}:$version"
  }

}
