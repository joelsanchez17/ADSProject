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

        // Wait for the pipeline to fill up (5 stages: IF, ID, EX, MEM, WB)
        dut.clock.step(5)

        // --- Data Forwarding & Arithmetic Tests (Instructions 1-4) ---
        // 1. ADDI x1, x0, 10
        dut.io.result.expect(10.U)
        dut.clock.step(1)
        // 2. ADDI x2, x0, 5
        dut.io.result.expect(5.U)
        dut.clock.step(1)
        // 3. ADD x3, x1, x2 (Forwarding from 1 and 2 to EX stage)
        dut.io.result.expect(15.U) // 10 + 5
        dut.clock.step(1)
        // 4. SUB x4, x3, x2 (Forwarding x3 from MEM, x2 from WB/already written)
        dut.io.result.expect(10.U) // 15 - 5
        dut.clock.step(1)

        // --- Load/Store & Stall Test (Instructions 5-7) ---
        // 5. SW x3, 4(x1)
        // SW does not write to a register, result should be 0 (or no change).
        //dut.io.result.expect(0.U)
        dut.clock.step(1)
        // 6. LW x5, 4(x1) (Loads 15)
        dut.io.result.expect(15.U)
        dut.clock.step(1)
        // 7. ADDI x6, x5, 1 (Data Hazard: x5 from LW. Should stall 1 cycle)
        // We expect the pipeline to stall, so this step takes 2 cycles to reach WB.
        dut.clock.step(1) // NOP/Stall (result from previous instruction, 15.U)
        dut.io.result.expect(16.U) // 15 + 1 (Result of ADDI x6)
        dut.clock.step(1)

        // 8. NOP
        dut.io.result.expect(0.U)
        dut.clock.step(1)

        // --- Branch Not Taken Test (Instructions 9-11) ---
        // 9. ADDI x7, x0, 1
        dut.io.result.expect(1.U)
        dut.clock.step(1)
        // 10. BEQ x7, x0, 0x000 (1 == 0 is FALSE) -> Branch NOT taken.
        // BEQ does not write to a register, result should be 0.
        //dut.io.result.expect(0.U)
        dut.clock.step(1)
        // 11. ADDI x8, x0, 100 (This is the next sequential instruction)
        dut.io.result.expect(100.U)
        dut.clock.step(1)

        // --- JUMP and Flush Test (Instructions 12-15) ---
        // 12. JUMP 0x40 (Flush instructions at 0x30, 0x34, 0x38)
        // JUMP does not write to a register, result should be 0.
        //dut.io.result.expect(0.U)
        dut.clock.step(2)
        // (x9, x10, x11 are flushed, but the result in WB is the jump result (0))
        // The jump flushes 3 instructions (assuming 3 instructions are in IF/ID/EX when JUMP is in MEM).
        // Since we check the WB stage, we only see the jump instruction itself (0).
        // Instructions 13, 14, 15 (at 0x30, 0x34, 0x38) are flushed and their results (1) are skipped.

        // --- Branch Taken Test (Instruction 16-17) ---
        // 16. BEQ x0, x0, 0x8 (0 == 0 is TRUE) -> Branch IS taken to 0x48.
        // BEQ does not write to a register, result should be 0.
        /dut.io.result.expect(0.U)
        dut.clock.step(1)
        // (The instruction at 0x44 (addi x12) is flushed)
        // The branch flushes 1 instruction (assuming branch resolution in EX).
        // The next result to appear is the branch target.
        dut.clock.step(2) // NOP/Flushed instruction (result from BEQ is still 0)
        // 17. ADDI x13, x0, 13 (Branch Target)
        dut.io.result.expect(13.U)
        dut.clock.step(1)
    }
}