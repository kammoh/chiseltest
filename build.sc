// SPDX-License-Identifier: Apache-2.0

import mill._
import mill.scalalib._
import mill.scalalib.scalafmt._
import mill.scalalib.publish._
import coursier.maven.MavenRepository

object chiseltest extends mill.Cross[chiseltestCrossModule]("2.13.13")

object firrtl2 extends someModule with SbtModule with PublishModule with ScalafmtModule {
  def scalaVersion = "2.13.13"

  override def ivyDeps = T {
    Agg(
      ivyDep("scalatest"),
      // ivyDep("scalacheck"),
      ivyDep("scopt"),
      ivyDep("os-lib"),
      ivyDep("json4s-native"),
      ivyDep("commons-text"),
      ivyDep("scala-parallel-collections")
    )
  }

  def publishVersion = getOrgAndVersion("chisel")._2

  def pomSettings = T {
    PomSettings(
      description = artifactName(),
      organization = "edu.berkeley.cs",
      url = "https://github.com/ucb-bar/firrtl2",
      licenses = Seq(License.`BSD-3-Clause`),
      versionControl = VersionControl.github("ucb-bar", "firrtl2"),
      developers = Seq(
        Developer("ducky64", "Richard Lin", "https://aspire.eecs.berkeley.edu/author/rlin/")
      )
    )
  }
}

val defaultVersions = Map(
  "chisel" -> ("org.chipsalliance", "7.0.0-M1+68-96407e96+20240416-1208-SNAPSHOT"),
  "chisel-plugin" -> ("org.chipsalliance:::", "$chisel"),
  "firrtl2" -> ("edu.berkeley.cs", "6.0-SNAPSHOT"),
  "scalatest" -> ("org.scalatest", "3.2.17"),
  "sscalacheck" -> ("org.scalatestplus::scalacheck-1-17", "3.2.17.0"),
  "scopt" -> ("com.github.scopt", "4.1.0"),
  "os-lib" -> ("com.lihaoyi", "0.9.2"),
  "json4s-native" -> ("org.json4s", "4.1.0-M4+"),
  "commons-text" -> ("org.apache.commons:", "1.10.0"),
  "scala-parallel-collections" -> ("org.scala-lang.modules", "1.0.4"),
  "jna" -> ("net.java.dev.jna:", "5.14.0")
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

def ivyDep(dep: String) = {
  val (org, version) = getOrgAndVersion(dep)
  ivy"$org${if (!org.contains(":")) "::" + dep else if (org.endsWith(":")) dep else ""}:$version"
}

trait chiseltestCrossModule
    extends Cross.Module[String]
    with someModule
    with SbtModule
    with PublishModule
    with ScalafmtModule {

  val crossScalaVersion = crossValue

  def scalaVersion = crossScalaVersion

  override def millSourcePath = super.millSourcePath / os.up

  // 2.12.12 -> Array("2", "12", "12") -> "12" -> 12
  private def majorVersion = crossScalaVersion.split('.')(1).toInt

  override def scalacOptions = T {
    super.scalacOptions() ++ Seq(
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-language:reflectiveCalls", // required by SemanticDB compiler plugin
      // do not warn about firrtl imports, once the firrtl repo is removed, we will need to import the code
      "-Wconf:cat=deprecation&msg=Importing from firrtl is deprecated:s",
      // do not warn about firrtl deprecations
      "-Wconf:cat=deprecation&msg=will not be supported as part of the migration to the MLIR-based FIRRTL Compiler:s"
    )
  }

  override def javacOptions = T {
    super.javacOptions() // ++ Seq("-source", "1.8", "-target", "1.8")
  }

  override def ivyDeps = Agg(
    ivyDep("chisel"),
    ivyDep("firrtl2"),
    ivyDep("jna"),
    ivyDep("scalatest")
  )

  override def scalacPluginIvyDeps = Agg(ivyDep("chisel-plugin"))

  object test extends ScalaTests with TestModule.ScalaTest with ScalafmtModule {
    override def ivyDeps = Agg(ivyDep("chisel"))
  }

  def publishVersion = getOrgAndVersion("chisel")._2

  def pomSettings = T {
    PomSettings(
      description = artifactName(),
      organization = "edu.berkeley.cs",
      url = "https://github.com/ucb-bar/chiseltest",
      licenses = Seq(License.`BSD-3-Clause`),
      versionControl = VersionControl.github("ucb-bar", "chiseltest"),
      developers = Seq(
        Developer("ducky64", "Richard Lin", "https://aspire.eecs.berkeley.edu/author/rlin/")
      )
    )
  }

  override def moduleDeps = super.moduleDeps

  // make mill publish sbt compatible package
  override def artifactName = "chiseltest"
}

trait someModule extends ScalaModule {
  override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/releases"),
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots"),
      MavenRepository("https://s01.oss.sonatype.org/content/repositories/releases"),
      MavenRepository("https://s01.oss.sonatype.org/content/repositories/snapshots"),
      MavenRepository(s"file://${os.home}/.m2/repository")
    )
  }

}
