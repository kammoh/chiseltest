// SPDX-License-Identifier: Apache-2.0

package chiseltest.iotesters.examples

import chisel3._
import chisel3.util.experimental.loadMemoryFromFileInline
import chiseltest._
import chiseltest.iotesters._
import chiseltest.simulator.RequiresVerilator
import firrtl2.options.TargetDirAnnotation
import org.scalatest.freespec.AnyFreeSpec

class UsesMem(memoryDepth: Int, memoryType: Bits, fileName: String) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(memoryType.getWidth.W))
    val value = Output(memoryType)
    val value2 = Output(memoryType)
  })

  val memory = Mem(memoryDepth, memoryType)

  loadMemoryFromFileInline(memory, fileName)

  io.value := memory(io.address)

  val low = Module(new UsesMemLow(memoryDepth, memoryType))

  low.io.address := io.address
  io.value2 := low.io.value
}

object UsesMem {
  lazy val Mem1 = {
    val memResourcePath = "/iotesters/mem1.txt"
    val memFilePath = new java.io.File(memResourcePath.split("/").last)
    firrtl.util.BackendCompilationUtilities.copyResourceToFile(memResourcePath, memFilePath)
    memFilePath.getCanonicalPath
  }
}

class UsesMemLow(memoryDepth: Int, memoryType: Data) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(memoryType.getWidth.W))
    val value = Output(memoryType)
  })

  val memory = Mem(memoryDepth, memoryType)

  val memFile = new java.io.File("mem2.txt")
  firrtl.util.BackendCompilationUtilities.copyResourceToFile("/iotesters/mem2.txt", memFile)

  loadMemoryFromFileInline(memory, memFile.getCanonicalPath)

  io.value := memory(io.address)
}

class LoadMemoryFromFileTester(c: UsesMem) extends PeekPokeTester(c) {
  for (addr <- 0 until 8) {
    poke(c.io.address, addr)
    step(1)
    println(f"peek from $addr ${peek(c.io.value)}%x ${peek(c.io.value2)}%x")
    expect(c.io.value, addr)
    expect(c.io.value2, 7 - addr)
  }
}

class LoadMemoryFromFileSpec extends AnyFreeSpec with ChiselScalatestTester {
  "Users can specify a source file to load memory from" taggedAs RequiresVerilator in {

    val targetDir = TargetDirAnnotation("test_run_dir/load_mem_test")
    test(new UsesMem(memoryDepth = 8, memoryType = UInt(16.W), UsesMem.Mem1))
      .withAnnotations(Seq(VerilatorBackendAnnotation, targetDir))
      .runPeekPoke(new LoadMemoryFromFileTester(_))
  }

  "Treadle also supports loadFromFileInline using relative paths" in {
    // An absolute path
    test(new UsesMem(memoryDepth = 8, memoryType = UInt(16.W), UsesMem.Mem1))
      .runPeekPoke(new LoadMemoryFromFileTester(_))
  }
}
