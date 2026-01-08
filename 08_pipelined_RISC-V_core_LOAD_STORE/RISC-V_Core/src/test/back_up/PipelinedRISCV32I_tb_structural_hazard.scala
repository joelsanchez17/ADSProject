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

        // --- Original Instructions ---
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
        // dut.io.result.expect(0.U)  // sw x2, 4(x1)
        dut.clock.step(1)
        dut.io.result.expect(0.U)     // ADDI x0, x0, 0 (NOP)
        dut.clock.step(1)
        dut.io.result.expect(0.U)     // ADDI x0, x0, 0 (NOP)
        dut.clock.step(1)
        dut.io.result.expect(0.U)     // ADDI x0, x0, 0 (NOP)
        dut.clock.step(1)
        dut.io.result.expect(5.U)     // lw x10, 4(x1)
        dut.clock.step(1)

        // --- New Forwarding Tests ---

        // 1. ADDI x11, x0, 10
        dut.io.result.expect(10.U)
        dut.clock.step(1)
        // 2. ADD x12, x11, x11 (Needs x11 from MEM)
        dut.io.result.expect(20.U)
        dut.clock.step(1)
        // 3. ADD x13, x12, x11 (Needs x12 from MEM, x11 from WB)
        dut.io.result.expect(30.U)
        dut.clock.step(1)

        // --- New "Clean" Load-Use Hazard (Stall) Test ---
        // This test avoids x0 as a source register.

        // 1. ADDI x16, x1, 100
        // Set up base address in x16 (t1) = x1 (4) + 100 = 104
        dut.io.result.expect(104.U)
        dut.clock.step(1)

        // 2. ADDI x17, x2, 40
        // Set up data in x17 (t2) = x2 (5) + 40 = 45
        dut.io.result.expect(45.U)
        dut.clock.step(1)

        // 3. SW x17, 8(x16)
        // Store 45 into mem[112] (104 + 8). No register write.
        //dut.io.result.expect(0.U)
        dut.clock.step(1)

        // 4. NOP
        // NOP to avoid the SW/ID-Stage bug.
        dut.io.result.expect(0.U)
        dut.clock.step(1)

        // 5. NOP
        // Another NOP for safety.
        dut.io.result.expect(0.U)
        dut.clock.step(1)

        // 6. LW x18, 8(x16)
        // Load value from mem[112] into x18 (t3).
        // It should be 45.
        dut.io.result.expect(45.U)
        dut.clock.step(1)

        // 7. STALL CYCLE
        // The *next* instruction (ADDI x19, x18, 5) is detected in ID
        // and depends on the LW in EX. The pipeline stalls.
        // A bubble (NOP) is inserted, which now arrives at WB.
        //dut.io.result.expect(0.U)     // Bubble (NOP)
        dut.clock.step(1)

        // 8. ADDI x19, x18, 5
        // The stalled ADDI finally completes.
        // It gets x18=45 (forwarded from MEM/WB).
        // Result: 45 + 5 = 50
        dut.io.result.expect(50.U)
        dut.clock.step(1)
    }
}
}