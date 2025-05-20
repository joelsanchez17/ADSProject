// ADS I Class Project
// Multi-Cycle RISC-V Core
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 12/19/2023 by Tobias Jauch (@tojauch)

package MultiCycleRV32I_Tester

import chisel3._
import chiseltest._
import core_tile._
import org.scalatest.flatspec.AnyFlatSpec

class MultiCycleRISCV32ITest extends AnyFlatSpec with ChiselScalatestTester {
  "MultiCycleRV32I_Tester" should "execute the given binary instructions correctly" in {
    test(new MultiCycleRV32Icore("src/test/programs/BinaryFile")).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      //dut.clock.setTimeout(100)

      val expectedResults = Seq(
          5,               // ADDI x1, x0, 5      => x1 = 5
          10,              // ADDI x2, x0, 10     => x2 = 10
          15,              // ADD x3, x1, x2      => x3 = x1 + x2 = 5 + 10
          -5,              // SUB x3, x1, x2      => x3 = x1 - x2 = -5 (signed)
//          15,              // XOR x3, x1, x2      => 0b0101 ^ 0b1010 = 0b1111 = 15
//        15,              // OR x3, x1, x2       => 0b0101 | 0b1010 = 0b1111 = 15
          0,               // AND x3, x1, x2      => 0b0101 & 0b1010 = 0
          1,               // SLT x3, x1, x2      => 5 < 10 = 1 (signed)
//        1,               // SLTU x3, x1, x2     => 5 < 10 = 1 (unsigned)
//        5120,            // SLL x3, x1, x2      => 5 << (10 & 0x1F) = 5 << 10
//        0,               // SRL x3, x1, x2      => 5 >> 10 = 0 (logical)
//        0                // SRA x3, x1, x2      => 5 >>> 10 = 0 (arithmetic)
      )
      var first = true
      for (expected <- expectedResults) {
        if (first) {
          dut.clock.step(4)
          first = false
        } else {
          dut.clock.step(5)
        }
        dut.io.check_res.expect(expected.S)
      }


//     for (expected <- expectedResults) {
//        dut.clock.step(4) // enough to complete all stages
//        dut.io.check_res.expect(expected.U)
//      }
    }
  }
}
