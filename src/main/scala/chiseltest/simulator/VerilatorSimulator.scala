// SPDX-License-Identifier: Apache-2.0

package chiseltest.simulator

import firrtl2._
import firrtl2.annotations._
import chiseltest.simulator.jna._
import chiseltest.simulator.Utils.quoteCmdArgs

case object VerilatorBackendAnnotation extends SimulatorAnnotation {
  override def getSimulator: Simulator = VerilatorSimulator
}

/** verilator specific options */
trait VerilatorOption extends NoTargetAnnotation

/** adds flags to the invocation of verilator */
case class VerilatorFlags(flags: Seq[String]) extends VerilatorOption

/** adds flags to the C++ compiler in the Makefile generated by verilator */
case class VerilatorCFlags(flags: Seq[String]) extends VerilatorOption

/** adds flags to the linker in the Makefile generated by verilator */
case class VerilatorLinkFlags(flags: Seq[String]) extends VerilatorOption

private object VerilatorSimulator extends Simulator {
  override def name: String = "verilator"

  /** is this simulator installed on the local machine? */
  override def isAvailable: Boolean = {
    val binaryFound = os.proc("which", "verilator").call().exitCode == 0
    binaryFound && majorVersion >= 4
  }

  override def supportsCoverage = true
  override def supportsLiveCoverage = true
  override def waveformFormats = Seq(WriteVcdAnnotation, WriteFstAnnotation)

  /** search the local computer for an installation of this simulator and print versions */
  def findVersions(): Unit = {
    if (isAvailable) {
      val (maj, min) = version
      println(s"Found Verilator $maj.$min")
    }
  }

  // example version string1: Verilator 4.038 2020-07-11 rev v4.038
  // example version string2: Verilator 5.002.p 2022-11-15 rev v5.002-12-g58821e0eb
  private lazy val version: (Int, Int) = { // (major, minor)
    val versionSplitted = os
      .proc(
        if (JNAUtils.isWindows) { "verilator_bin" }
        else { "verilator" },
        "--version"
      )
      .call()
      .out
      .trim()
      .split(' ')
    assert(
      versionSplitted.length > 1 && versionSplitted.head == "Verilator",
      s"Unknown verilator version string: ${versionSplitted.mkString(" ")}"
    )

    val (maj, min) = versionSplitted(1).split('.').map(_.trim) match {
      case Array(majStr, minStr) =>
        assert(majStr.length == 1 && minStr.length == 3, s"${majStr}.${minStr} is not of the expected format: D.DDD")
        (majStr.toInt, minStr.toInt)
      case Array(majStr, minStr, s) =>
        assert(majStr.length == 1 && minStr.length == 3, s"${majStr}.${minStr} is not of the expected format: D.DDD.s+")
        (majStr.toInt, minStr.toInt)
      case s =>
        assert(false, s"${s} is not of the expected format: D.DDD or D.DDD.s+")
        (0, 0)
    }
    (maj, min)
  }

  private def majorVersion: Int = version._1
  private def minorVersion: Int = version._2

  /** start a new simulation
    *
    * @param state
    *   LoFirrtl circuit + annotations
    */
  override def createContext(state: CircuitState): SimulatorContext = {
    val simName = s"${VerilatorSimulator.name} ${VerilatorSimulator.majorVersion}.${VerilatorSimulator.minorVersion}"
    Caching.cacheSimulationBin(simName, state, createContextFromScratch, recreateCachedContext)
  }

  private def getSimulatorArgs(state: CircuitState): Array[String] = {
    state.annotations.view.collect { case PlusArgsAnnotation(args) => args }.flatten.toArray
  }

  private def recreateCachedContext(state: CircuitState): SimulatorContext = {
    // we will create the simulation in the target directory
    val targetDir = Compiler.requireTargetDir(state.annotations)
    val toplevel = TopmoduleInfo(state.circuit)

    val libFilename = if (JNAUtils.isWindows) { "V" + toplevel.name + ".exe" }
    else { "V" + toplevel.name }
    val libPath = targetDir / "verilated" / libFilename
    // the binary we created communicates using our standard IPC interface
    val coverageAnnos = loadCoverageAnnos(targetDir)
    val coverageFile = targetDir / "coverage.dat"
    def readCoverage(): List[(String, Long)] = {
      assert(os.exists(coverageFile), s"Could not find `$coverageFile` file!")
      VerilatorCoverage.loadCoverage(coverageAnnos, coverageFile, (majorVersion, minorVersion))
    }

    val args = getSimulatorArgs(state)
    val lib = JNAUtils.compileAndLoadJNAClass(libPath)
    new JNASimulatorContext(lib, targetDir, toplevel, VerilatorSimulator, args, Some(readCoverage))
  }

  private def createContextFromScratch(state: CircuitState): SimulatorContext = {
    // we will create the simulation in the target directory
    val targetDir = Compiler.requireTargetDir(state.annotations)
    val toplevel = TopmoduleInfo(state.circuit)

    // verbose output to stdout if we are in debug mode
    val verbose = state.annotations.contains(SimulatorDebugAnnotation)

    // Create the header files that verilator needs + a custom harness
    val waveformExt = Simulator.getWavformFormat(state.annotations)
    val cppHarness = generateHarness(targetDir, toplevel, waveformExt, verbose)

    // compile low firrtl to System Verilog for verilator to use
    val verilogState = Compiler.lowFirrtlToSystemVerilog(state, VerilatorCoverage.CoveragePasses)

    // turn SystemVerilog into C++ simulation
    val verilatedDir = runVerilator(toplevel.name, targetDir, cppHarness, state.annotations, verbose)

    // patch the coverage cpp provided with verilator only if Verilator is older than 4.202
    // Starting with Verilator 4.202, the whole patch coverage hack is no longer necessary
    require(
      majorVersion >= 4,
      s"Unsupported Verilator version: $majorVersion.$minorVersion. Only major version 4 and up is supported."
    )

    if (majorVersion == 4 && minorVersion < 202) {
      VerilatorPatchCoverageCpp(verilatedDir, majorVersion, minorVersion)
    }

    val libPath = compileSimulation(topName = toplevel.name, verilatedDir, verbose)

    // the binary we created communicates using our standard IPC interface
    val coverageAnnos = VerilatorCoverage.collectCoverageAnnotations(verilogState.annotations)
    if (Caching.shouldCache(state)) {
      saveCoverageAnnos(targetDir, coverageAnnos)
    }
    val coverageFile = targetDir / "coverage.dat"
    def readCoverage(): List[(String, Long)] = {
      assert(os.exists(coverageFile), s"Could not find `$coverageFile` file!")
      VerilatorCoverage.loadCoverage(coverageAnnos, coverageFile, (majorVersion, minorVersion))
    }

    val args = getSimulatorArgs(state)
    val lib = JNAUtils.compileAndLoadJNAClass(libPath)
    new JNASimulatorContext(lib, targetDir, toplevel, VerilatorSimulator, args, Some(readCoverage))
  }

  private def saveCoverageAnnos(targetDir: os.Path, annos: AnnotationSeq): Unit = {
    os.write.over(targetDir / "coverageAnnotations.json", JsonProtocol.serialize(annos))
  }

  private def loadCoverageAnnos(targetDir: os.Path): AnnotationSeq = {
    JsonProtocol.deserialize((targetDir / "coverageAnnotations.json").toIO)
  }

  private def compileSimulation(topName: String, verilatedDir: os.Path, verbose: Boolean): os.Path = {
    val target = s"V$topName"
    val processorCount = Runtime.getRuntime.availableProcessors.toString
    val cmd = Seq("make", "-C", verilatedDir.toString(), "-j", processorCount, "-f", s"V$topName.mk", target)
    val ret = run(cmd, null, verbose)
    assert(
      ret.exitCode == 0,
      s"Compilation of verilator generated code failed for circuit $topName in work dir $verilatedDir"
    )
    val simBinary = if (JNAUtils.isWindows) { verilatedDir / s"${target}.exe" }
    else { verilatedDir / target }
    assert(os.exists(simBinary), s"Failed to generate simulation binary: $simBinary")
    simBinary
  }

  /** executes verilator in order to generate a C++ simulation */
  private def runVerilator(
    topName:    String,
    targetDir:  os.Path,
    cppHarness: String,
    annos:      AnnotationSeq,
    verbose:    Boolean
  ): os.Path = {
    val verilatedDir = targetDir / "verilated"

    removeOldCode(verilatedDir, verbose)
    val flagAnnos = VerilatorLinkFlags(JNAUtils.ldFlags) +: VerilatorCFlags(JNAUtils.ccFlags) +: annos
    val flags = generateFlags(topName, verilatedDir, flagAnnos)
    val cmd = List(
      if (JNAUtils.isWindows) { "verilator_bin" }
      else { "verilator" },
      "--cc",
      "--exe",
      cppHarness
    ) ++ flags ++ List(s"$topName.sv")
    val ret = run(cmd, targetDir, verbose)

    assert(ret.exitCode == 0, s"verilator command failed on circuit ${topName} in work dir $targetDir")
    verilatedDir
  }

  private def removeOldCode(verilatedDir: os.Path, verbose: Boolean): Unit = {
    if (os.exists(verilatedDir)) {
      if (verbose) println(s"Deleting stale Verilator object directory: $verilatedDir")
      os.remove.all(verilatedDir)
    }
  }

  private def run(cmd: Seq[String], cwd: os.Path, verbose: Boolean): os.CommandResult = {
    if (verbose) {
      // print the command and pipe the output to stdout
      println(quoteCmdArgs(cmd))
      os.proc(cmd)
        .call(cwd = cwd, stdout = os.ProcessOutput.Readlines(println), stderr = os.ProcessOutput.Readlines(println))
    } else {
      os.proc(cmd).call(cwd = cwd)
    }
  }

  private def DefaultCFlags(topName: String) = List(
    "-Os",
    "-DVL_USER_STOP",
    "-DVL_USER_FATAL",
    "-DVL_USER_FINISH", // this is required because we ant to overwrite the vl_finish function!
    s"-DTOP_TYPE=V$topName"
  )

  private def DefaultFlags(topName: String, verilatedDir: os.Path, cFlags: Seq[String], ldFlags: Seq[String]) = List(
    "--assert", // we always enable assertions
    "--coverage-user", // we always enable use coverage
    "-Wno-fatal",
    "-Wno-WIDTH",
    "-Wno-STMTDLY",
    "--top-module",
    topName,
    "+define+TOP_TYPE=V" + topName,
    // flags passed to the C++ compiler
    "-CFLAGS",
    cFlags.mkString(" "),
    // name of the directory that verilator generates the C++ model + Makefile in
    "-Mdir",
    verilatedDir.toString()
  ) ++ (if (ldFlags.nonEmpty) Seq("-LDFLAGS", ldFlags.mkString(" ")) else Seq())

  // documentation of Verilator flags: https://verilator.org/guide/latest/exe_verilator.html#
  private def generateFlags(topName: String, verilatedDir: os.Path, annos: AnnotationSeq): Seq[String] = {
    val waveformExt = Simulator.getWavformFormat(annos)
    val targetDir = Compiler.requireTargetDir(annos)

    // generate C flags
    val userCFlags = annos.collect { case VerilatorCFlags(f) => f }.flatten
    // some older versions of Verilator seem to not set VM_TRACE_FST correctly, thus:
    val fstCFlag = if (waveformExt == "fst") Seq("-DVM_TRACE_FST=1") else Seq()
    val cFlags = DefaultCFlags(topName) ++ fstCFlag ++ userCFlags
    val ldFlags = annos.collect { case VerilatorLinkFlags(f) => f }.flatten

    // combine all flags
    val userFlags = annos.collectFirst { case VerilatorFlags(f) => f }.getOrElse(Seq.empty)
    val waveformFlags = waveformExt match {
      case "vcd" => List("--trace")
      case "fst" => List("--trace-fst")
      case ""    => List()
      case other => throw new RuntimeException(s"Unsupported waveform format: $other")
    }
    val flags =
      DefaultFlags(topName, verilatedDir, cFlags, ldFlags) ++ waveformFlags ++ BlackBox.fFileFlags(
        targetDir
      ) ++ userFlags
    flags
  }

  private def generateHarness(
    targetDir:   os.Path,
    toplevel:    TopmoduleInfo,
    waveformExt: String,
    verbose:     Boolean
  ): String = {
    val topName = toplevel.name

    // create a custom c++ harness
    val cppHarnessFileName = s"${topName}-harness.cpp"
    val vcdFile = targetDir / (s"$topName." + waveformExt)
    val code = VerilatorCppJNAHarnessGenerator.codeGen(
      toplevel,
      vcdFile,
      targetDir,
      majorVersion = majorVersion,
      minorVersion = minorVersion,
      verbose = verbose
    )
    os.write.over(targetDir / cppHarnessFileName, code)

    cppHarnessFileName
  }
}

/** Changes the file generated by verilator to generate per instance and not per module coverage. This is required in
  * order to satisfy our generic TestCoverage interface for which the simulator needs to return per instance coverage
  * counts. See: https://github.com/verilator/verilator/issues/2793
  */
private object VerilatorPatchCoverageCpp {
  private val CallNeedle = "VL_COVER_INSERT("
  private val CallReplacement = "CHISEL_VL_COVER_INSERT("
  private val CoverageStartNeedle = "// Coverage"

  def apply(dir: os.Path, major: Int, minor: Int): Unit = {
    assert(major == 4 && minor < 202, "Starting with Verilator 4.202 this hack is no longer necessary!")
    val files = loadFiles(dir)
    files.foreach { case (cppFile, lines) =>
      replaceCoverage(cppFile, lines, minor)
      doWrite(cppFile, lines)
    }
  }

  private def replaceCoverage(cppFile: os.Path, lines: Array[String], minor: Int): Unit = {
    // we add our code at the beginning of the coverage section
    val coverageStart = findLine(CoverageStartNeedle, cppFile, lines)
    lines(coverageStart) += "\n" + CustomCoverInsertCode(withCtxPtr = minor >= 200) + "\n"

    // then we replace the call
    val call = findLine(CallNeedle, cppFile, lines)
    val callLine = lines(call).replace(CallNeedle, CallReplacement)
    lines(call) = callLine
  }

  private def loadFiles(dir: os.Path): Seq[(os.Path, Array[String])] = {
    if (!os.exists(dir) || !os.isDir(dir)) {
      error(s"Failed to find directory $dir")
    }

    // find all cpp files generated by verilator
    val cppFiles = os.list(dir).filter(os.isFile).filter { f =>
      val name = f.last
      name.startsWith("V") && name.endsWith(".cpp")
    }

    // filter out files that do not contain any coverage definitions
    cppFiles.map(f => (f, FileUtils.getLines(f).toArray)).filter { case (name, lines) =>
      findLineOption(CoverageStartNeedle, lines).isDefined
    }
  }

  private def findLineOption(needle: String, lines: Iterable[String]): Option[Int] =
    lines.map(_.trim).zipWithIndex.find(_._1.startsWith(needle)).map(_._2)

  private def findLine(needle: String, filename: os.Path, lines: Iterable[String]): Int =
    findLineOption(needle, lines).getOrElse(error(s"Failed to find line `$needle` in $filename."))

  private def doWrite(file: os.Path, lines: Array[String]): Unit = os.write.over(file, lines.mkString("\n"))

  private def error(msg: String): Nothing = {
    throw new RuntimeException(msg + "\n" + "Please file an issue and include the output of `verilator --version`")
  }

  private def CustomCoverInsertCode(withCtxPtr: Boolean): String = {
    val argPrefix = if (withCtxPtr) "VerilatedCovContext* covcontextp, " else ""
    val callArgPrefix = if (withCtxPtr) "covcontextp, " else ""
    val cov = if (withCtxPtr) "covcontextp->" else "VerilatedCov::"

    s"""#ifndef CHISEL_VL_COVER_INSERT
       |#define CHISEL_VL_COVER_INSERT(${callArgPrefix}countp, ...) \\
       |    VL_IF_COVER(${cov}_inserti(countp); ${cov}_insertf(__FILE__, __LINE__); \\
       |                chisel_insertp(${callArgPrefix}"hier", name(), __VA_ARGS__))
       |
       |#ifdef VM_COVERAGE
       |static void chisel_insertp(${argPrefix}
       |  const char* key0, const char* valp0, const char* key1, const char* valp1,
       |  const char* key2, int lineno, const char* key3, int column,
       |  const char* key4, const std::string& hier_str,
       |  const char* key5, const char* valp5, const char* key6, const char* valp6,
       |  const char* key7 = nullptr, const char* valp7 = nullptr) {
       |
       |    std::string val2str = vlCovCvtToStr(lineno);
       |    std::string val3str = vlCovCvtToStr(column);
       |    ${cov}_insertp(
       |        key0, valp0, key1, valp1, key2, val2str.c_str(),
       |        key3, val3str.c_str(), key4, hier_str.c_str(),
       |        key5, valp5, key6, valp6, key7, valp7,
       |        // turn on per instance cover points
       |        "per_instance", "1");
       |}
       |#endif // VM_COVERAGE
       |#endif // CHISEL_VL_COVER_INSERT
       |""".stripMargin
  }
}
