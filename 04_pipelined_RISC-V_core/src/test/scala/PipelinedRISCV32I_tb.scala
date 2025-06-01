// ADS I Class Project
// Pipelined RISC-V Core
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 12/19/2023 by Tobias Jauch (@tojauch)

package PipelinedRV32I_Tester

import chisel3._
import chiseltest._
import PipelinedRV32I._
import org.scalatest.flatspec.AnyFlatSpec

class PipelinedRISCV32ITest extends AnyFlatSpec with ChiselScalatestTester {

"PipelinedRV32I_Tester" should "work" in {
    test(new PipelinedRV32I("src/test/programs/BinaryFile")).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

      //dut.clock.setTimeout(100)

      val expectedResults = Seq(
        5,               // ADDI x1, x0, 5      => x1 = 5
        10,              // ADDI x2, x0, 10     => x2 = 10
        0,               // ADDI x0, x0, 0
        15,              // ADD x3, x1, x2      => x3 = x1 + x2 = 5 + 10
        -5,              // SUB x3, x1, x2      => x3 = x1 - x2 = -5 (signed)
        0,               // AND x3, x1, x2      => 0b0101 & 0b1010 = 0
        1,               // SLT x3, x1, x2      => 5 < 10 = 1 (signed)
        1,               // SLTU x3, x1, x2     => 5 < 10 = 1 (unsigned)
        5120,            // SLL x3, x1, x2      => 5 << (10 & 0x1F) = 5 << 10
        0,               // SRL x3, x1, x2      => 5 >> 10 = 0 (logical)
        0,                // SRA x3, x1, x2      => 5 >>> 10 = 0 (arithmetic)
        15,              // XOR x3, x1, x2      => 0b0101 ^ 0b1010 = 0b1111 = 15
        15              // OR x3, x1, x2       => 0b0101 | 0b1010 = 0b1111 = 15
      )
      var first = true
      for (expected <- expectedResults) {
        if (first) {
          dut.clock.step(4)
          first = false
        } else {
          dut.clock.step(1)
        }
//        dut.io.result.expect(expected.U)
        dut.io.result.expect((expected & 0xFFFFFFFFL).U)
      }

    }
}
}

           



