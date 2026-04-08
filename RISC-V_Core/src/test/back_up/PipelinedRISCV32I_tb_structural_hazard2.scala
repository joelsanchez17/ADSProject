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


            // --- Program Instructions ---
            // 1. ADDI x1, x0, 100   (x1 = 100)
            // 2. ADDI x2, x0, 5     (x2 = 5)
            // 3. SW x2, 0(x1)       (mem[100] = 5)
            // 4. ADDI x3, x0, 8     (x3 = 8)
            // 5. SW x3, 4(x1)       (mem[104] = 8)
            // 6. NOP
            // 7. NOP
            // 8. LW x5, 0(x1)       (x5 = 5)
            // 9. ADDI x6, x5, 1     (HAZARD: Stall!) -> x6 = 6
            // 10. LW x7, 4(x1)      (x7 = 8)
            // 11. ADD x8, x7, x6    (HAZARD: Stall!) -> x8 = 8 + 6 = 14
            // 12. LW x9, 0(x1)      (x9 = 5)
            // 13. NOP
            // 14. ADDI x10, x9, 1   (NO STALL) -> x10 = 5 + 1 = 6

            dut.clock.step(5)             // Wait for first instruction to reach WB

            // --- Setup Phase ---
            dut.io.result.expect(100.U)   // 1. ADDI x1, x0, 100
            dut.clock.step(1)
            dut.io.result.expect(5.U)     // 2. ADDI x2, x0, 5
            dut.clock.step(1)
            //dut.io.result.expect(0.U)     // 3. SW x2, 0(x1)
            dut.clock.step(1)
            dut.io.result.expect(8.U)     // 4. ADDI x3, x0, 8
            dut.clock.step(1)
            //dut.io.result.expect(0.U)     // 5. SW x3, 4(x1)
            dut.clock.step(1)
            dut.io.result.expect(0.U)     // 6. NOP
            dut.clock.step(1)
            dut.io.result.expect(0.U)     // 7. NOP
            dut.clock.step(1)

            // --- Stall Test 1 ---
            dut.io.result.expect(5.U)     // 8. LW x5, 0(x1)
            dut.clock.step(1)
            // STALL: ADDI x6 is in ID, LW x5 is in EX.
            // Hazard Unit inserts a bubble (NOP) into EX.
            // That bubble now arrives at WB.
            //dut.io.result.expect(0.U)     // 9a. STALL BUBBLE
            dut.clock.step(1)
            // The ADDI x6 instruction has now passed.
            // It received x5=5 from the MEM/WB stage.
            dut.io.result.expect(6.U)     // 9b. ADDI x6, x5, 1 (5 + 1)
            dut.clock.step(1)

            // --- Stall Test 2 ---
            dut.io.result.expect(8.U)     // 10. LW x7, 4(x1)
            dut.clock.step(1)
            // STALL: ADD x8 is in ID, LW x7 is in EX.
            // Hazard Unit inserts a bubble (NOP).
            //dut.io.result.expect(0.U)     // 11a. STALL BUBBLE
            dut.clock.step(1)
            // The ADD x8 instruction has now passed.
            // It received x7=8 (from MEM/WB) and x6=6 (from WB).
            dut.io.result.expect(14.U)    // 11b. ADD x8, x7, x6 (8 + 6)
            dut.clock.step(1)

            // --- No-Stall Test ---
            dut.io.result.expect(5.U)     // 12. LW x9, 0(x1)
            dut.clock.step(1)
            // The NOP instruction is in the EX stage.
            // The LW x9 is in the MEM stage.
            // The ADDI x10 is in the ID stage. No hazard!
            dut.io.result.expect(0.U)     // 13. NOP (The NOP itself, NOT a bubble)
            dut.clock.step(1)
            // The ADDI x10 instruction is in the EX stage.
            // The LW x9 is in the WB stage.
            // Forwarding from WB works. No stall occurred.
            dut.io.result.expect(6.U)     // 14. ADDI x10, x9, 1 (5 + 1)
            dut.clock.step(1)
        }
    }
}