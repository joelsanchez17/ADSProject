// ADS I Class Project
// Pipelined RISC-V Core with Hazard Detection and Resolution
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 05/21/2024 by Tobias Jauch (@tojauch)
// File updated on 09/22/2025 by Tharindu Samarakoon. (gug75kex@rptu.de)

package PipelinedRV32I_Tester

import chisel3._
import chiseltest._
import PipelinedRV32I._
import org.scalatest.flatspec.AnyFlatSpec

class PipelinedRISCV32ITest extends AnyFlatSpec with ChiselScalatestTester {

"PipelinedRV32I_Tester" should "work" in {
    test(new PipelinedRV32I("src/test/programs/BinaryFile")).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

        dut.clock.setTimeout(0)

        dut.clock.step(5)             // it is important to wait until the first instruction travelled through the entire pipeline

        dut.io.result.expect(0.U)     // ADDI x0, x0, 0
        dut.clock.step(1)
        dut.io.result.expect(4.U)     // ADDI x1, x0, 4
        dut.clock.step(1)
        dut.io.result.expect(5.U)     // ADDI x2, x0, 5
        dut.clock.step(1)
        dut.io.result.expect(2047.U)  // ADDI x3, x0, 2047
        dut.clock.step(1)
        dut.io.result.expect(16.U)    // ADDI x4, x0, 16
        dut.clock.step(1)
        dut.io.result.expect(0.U)     // ADDI x0, x0, 0
        dut.clock.step(1)
        dut.io.result.expect(9.U)     // ADD x5, x1, x2
        dut.clock.step(1)
        dut.io.result.expect(1.U)     // SUB x6, x2, x1
        dut.clock.step(1)
        dut.io.result.expect(13.U)    // ORI x7, x2, 0xc
        dut.clock.step(1)
        dut.io.result.expect(10.U)    // SLLI x8, x2, 1
        dut.clock.step(1)
        dut.io.result.expect(511.U)   // SRAI x9, x3, 2
        dut.clock.step(1)
        // dut.io.result.expect(0.U)    // sw x2, 4(x1)
        dut.clock.step(1)
        dut.io.result.expect(0.U)     // ADDI x0, x0, 0
        dut.clock.step(1)
        dut.io.result.expect(0.U)     // ADDI x0, x0, 0
        dut.clock.step(1)
        dut.io.result.expect(0.U)     // ADDI x0, x0, 0
        dut.clock.step(1)
        dut.io.result.expect(5.U)     // lw x10, 4(x1)
        dut.clock.step(1)
    }
  }
}