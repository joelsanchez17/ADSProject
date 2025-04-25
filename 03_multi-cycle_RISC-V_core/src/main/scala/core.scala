// ADS I Class Project
// Multi-Cycle RISC-V Core
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 05/10/2023 by Tobias Jauch (@tojauch)

/*
The goal of this task is to implement a 5-stage multi-cycle 32-bit RISC-V processor (without pipelining) supporting parts of the RV32I instruction set architecture. The RV32I core is relatively basic 
and does not include features like memory operations, exception handling, or branch instructions. It is designed for a simplified subset of the RISC-V ISA. It mainly 
focuses on ALU operations and basic instruction execution. 

    Instruction Memory:
        The CPU has an instruction memory (IMem) with 4096 words, each of 32 bits.
        The content of IMem is loaded from a binary file specified during the instantiation of the MultiCycleRV32Icore module.

    CPU Registers:
        The CPU has a program counter (PC) and a register file (regFile) with 32 registers, each holding a 32-bit value.
        Register x0 is hard-wired to zero.

    Microarchitectural Registers / Wires:
        Various signals are defined as either registers or wires depending on whether they need to be used in the same cycle or in a later cycle.

    Processor Stages:
        The FSM of the processor has five stages: fetch, decode, execute, memory, and writeback.
        The current stage is stored in a register named stage.

        Fetch Stage:
            The instruction is fetched from the instruction memory based on the current value of the program counter (PC).

        Decode Stage:
            Instruction fields such as opcode, rd, funct3, and rs1 are extracted.
            For R-type instructions, additional fields like funct7 and rs2 are extracted.
            Control signals (isADD, isSUB, etc.) are set based on the opcode and funct3 values.
            Operands (operandA and operandB) are determined based on the instruction type.

        Execute Stage:
            Arithmetic and logic operations are performed based on the control signals and operands.
            The result is stored in the aluResult register.

        Memory Stage:
            No memory operations are implemented in this basic CPU.

        Writeback Stage:
            The result of the operation (writeBackData) is written back to the destination register (rd) in the register file.
            The program counter (PC) is updated for the next instruction.

        Other:
            If the processor state is not in any of the defined stages, an assertion is triggered to indicate an error.

    Check Result:
        The final result (writeBackData) is output to the io.check_res signal.
        In the fetch stage, a default value of 0 is assigned to io.check_res.
*/

package core_tile

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile

class MultiCycleRV32Icore (BinaryFile: String) extends Module {
  val io = IO(new Bundle {
    val check_res = Output(UInt(32.W))
  })

  val fetch :: decode :: execute :: memory :: writeback :: Nil = Enum(5) // Enum datatype to define the stages of the processor FSM
  val stage = RegInit(fetch) 

  // -----------------------------------------
  // Instruction Memory
  // -----------------------------------------

  /** TODO: Implement the memory as described above */
  val IMem = Mem(4096, UInt(32.W))       // Creation of block-memory of 32 bits which can hold 4062 values
  loadMemoryFromFile(IMem, BinaryFile)   // Load File in Memory for initiation


  // -----------------------------------------
  // CPU Registers
  // -----------------------------------------

  /** TODO: Implement the program counter as a register, initialize with zero */

  val PC= RegInit(0.U(32.W))

  /** TODO: Implement the Register File as described above */

  val RegFile = Mem(32,UInt(32.W))
  RegFile(0) := 0.U                        //Register x0 is hard-wired to zero

  // -----------------------------------------
  // Microarchitectural Registers / Wires
  // -----------------------------------------

  // if signal is processed in the same cycle --> wire
  // is signal is used in a later cycle       --> register

  /** TODO: Implement the registers and wires you need in the individual stages of the processor  */

  val instReg = Reg(UInt(32.W))       //  IF/ID Fetch to Decode

  val rs1Data = Reg(UInt(32.W))       //  ID/EX Decode to Execute
  val rs2Data = Reg(UInt(32.W))

  val aluResult = Reg(UInt(32.U))     //  EX/MEM Execute to Memory Access

  val readData = Reg(UInt(32.U))      //  MEM/WB Memory Access to Writeback


  // IOs need default case
  io.check_res := "h_0000_0000".U


  // -----------------------------------------
  // Processor Stages
  // -----------------------------------------

  when (stage === fetch)
  {
  /** TODO: Implement fetch stage */
    instReg := Imem(PC<<2.U)

  } 
    .elsewhen (stage === decode)
  {
    /** TODO: Implement decode stage */
    val opcode = instr(6, 0)    // Operation to be perform (add, subtract, load from memory, etc)
    val rd = instr(11, 7)       // Destination register
    val funct3 = instr(14, 12)  // Shift right or left
    val rs1 = instr(19, 15)     // Source register 1
    val rs2 = instr(24, 20)     // Source register 2
    val funct7 = instr(31, 25)  // Complement of opcode (help to determine exactly what operation to do)
    // I-type
    val immI = instr(31, 20)
    val immI_sext = Cat(Fill(20, immI(11)), immI)  // Ask! transfor immI (12 bits) into a signal of 32 bits

  } 
    .elsewhen (stage === execute)
  {
  /** TODO: Implement execute stage */



  }
    .elsewhen (stage === memory)
  {

    // No memory operations implemented in this basic CPU

    // TODO: There might still something be missing here

  } 
    .elsewhen (stage === writeback)
  {

  /*
   * TODO: Implement Writeback stag
   */

  /*
   * TODO: Write result to output
   */

  }
    .otherwise 
  {

     // default case (needed for RTL-generation but should never be reached   

     assert(true.B, "Pipeline FSM must never be left")

  }

}

