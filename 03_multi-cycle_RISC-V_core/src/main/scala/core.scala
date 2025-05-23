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

  val regFile = Mem(32,UInt(32.W))
  regFile(0) := 0.U                        //Register x0 is hard-wired to zero

  // -----------------------------------------
  // Microarchitectural Registers / Wires
  // -----------------------------------------

  // if signal is processed in the same cycle --> wire
  // is signal is used in a later cycle       --> register

  /** TODO: Implement the registers and wires you need in the individual stages of the processor  */

  /**  IF/ID Fetch to Decode */
  val instReg = RegInit(0.U(32.W))

  /**  ID/EX Decode to Execute */
  val rdReg = Reg(UInt(5.W))
  val rs1Reg = Reg(UInt(5.W))
  val rs2Reg = Reg(UInt(5.W))
  val funct3Reg = Reg(UInt(3.W))
  val funct7Reg = Reg(UInt(7.W))
  val opcodeReg = Reg(UInt(7.W))

  /**  EX/MEM Execute to Memory Access */
  val rs1Data = Reg(UInt(32.W))
  val rs2Data = Reg(UInt(32.W))

  val operandA = Reg(UInt(32.W))      //defined in order to copy Task 2
  val operandB = Reg(UInt(32.W))

  /**  EX/MEM Memory Access to WriteBack */
  val aluResult = Reg(UInt(32.W))

  /** Control signals */
  val isADD  = RegInit(false.B)
  val isSUB  = RegInit(false.B)
  val isSLL  = RegInit(false.B)
  val isSLT  = RegInit(false.B)
  val isSLTU = RegInit(false.B)
  val isXOR  = RegInit(false.B)
  val isSRL  = RegInit(false.B)
  val isSRA  = RegInit(false.B)
  val isOR   = RegInit(false.B)
  val isAND  = RegInit(false.B)
  val isADDI = RegInit(false.B)


  // IOs need default case
  io.check_res := "h_0000_0000".U


  // -----------------------------------------
  // Processor Stages
  // -----------------------------------------

  when (stage === fetch)
  {
  /** TODO: Implement fetch stage */
    instReg := IMem(PC >> 2.U)
    stage:= decode
  } 
    .elsewhen (stage === decode)
  {
    /** TODO: Implement decode stage */

    opcodeReg := instReg(6, 0)
    rdReg := instReg(11, 7)
    funct3Reg := instReg(14, 12)
    rs1Reg := instReg(19, 15)
    rs2Reg := instReg(24, 20)
    funct7Reg := instReg(31, 25)

    val immI = instReg(31, 20)
    val immI_sext = Cat(Fill(20, immI(11)), immI)
    /** Functions */
    isADD  := (opcodeReg === "b0110011".U && funct3Reg === "b000".U && funct7Reg === "b0000000".U)
    isSUB  := (opcodeReg === "b0110011".U && funct3Reg === "b000".U && funct7Reg === "b0100000".U)
    // SLL Logical left shift rd = rs1 << (rs2 & 0x1F)
    isSLL  := (opcodeReg === "b0110011".U && funct3Reg === "b001".U && funct7Reg === "b0000000".U)
    // SLT set if less than (signed) rd = (rs1 < rs2)? 1 : 0
    isSLT  := (opcodeReg === "b0110011".U && funct3Reg === "b010".U && funct7Reg === "b0000000".U)
    // SLTU set if less than (unsigned) rd = (rs1 < rs2)? 1:0
    isSLTU := (opcodeReg === "b0110011".U && funct3Reg === "b011".U && funct7Reg === "b0000000".U)
    // XOR rd = rs1 ^ rs2 (only one set to 1)
    isXOR  := (opcodeReg === "b0110011".U && funct3Reg === "b100".U && funct7Reg === "b0000000".U)
    // SRL Logical right shift rd = rs1 >> (rs2 & 0x1F)
    isSRL  := (opcodeReg === "b0110011".U && funct3Reg === "b101".U && funct7Reg === "b0000000".U)
    // SRA Arithmetic right shift: preserves sign rd = rs1 >>> (rs & 0x1F)
    isSRA  := (opcodeReg === "b0110011".U && funct3Reg === "b101".U && funct7Reg === "b0100000".U)
    isOR   := (opcodeReg === "b0110011".U && funct3Reg === "b110".U && funct7Reg === "b0100000".U)
    isAND  := (opcodeReg === "b0110011".U && funct3Reg === "b111".U && funct7Reg === "b0100000".U)
    isADDI := (opcodeReg === "b0010011".U && funct3Reg === "b000".U)

    rs1Data := regFile(rs1Reg)                                   //go to regFile(address)
    rs2Data := Mux(isADDI, immI_sext, regFile(rs2Reg))           // if isADDI = true, rs2Data = immI_sext

    operandA := rs1Data
    operandB := rs2Data

    stage := execute
  } 
    .elsewhen (stage === execute)
  {
  /** TODO: Implement execute stage */
    when(isADDI) {
      aluResult := operandA + operandB
    }.elsewhen(isADD) {
      aluResult := operandA + operandB
    }.elsewhen(isSUB) {
      aluResult := operandA - operandB
    }.elsewhen(isSLL) {
      aluResult := operandA << operandB(4, 0)                          //operandA shifted left by operandB
    }.elsewhen(isSLT) {
      aluResult := Mux(operandA.asSInt < operandB.asSInt, 1.U, 0.U)    //if true 1, otherwise 0
    }.elsewhen(isSLTU) {
      aluResult := Mux(operandA < operandB, 1.U, 0.U)                  //if true 1, otherwise 0
    }.elsewhen(isXOR) {
      aluResult := operandA ^ operandB
    }.elsewhen(isSRL) {
      aluResult := operandA >> operandB(4, 0)
    }.elsewhen(isSRA) {
      aluResult := (operandA.asSInt() >> operandB(4,0)).asUInt
    }.elsewhen(isOR) {
      aluResult := operandA | operandB
    }.elsewhen(isAND) {
      aluResult := operandA & operandB
    }.otherwise {
      aluResult := 0.U                                                 // Default case for unimplemented instructions
    }

    stage := memory
  }
    .elsewhen (stage === memory)
  {
    /** No memory operations implemented in this basic CPU
     * TODO: There might still something be missing here */

    stage := writeback
  } 
    .elsewhen (stage === writeback)
  {

  /** TODO: Implement Writeback stage*/
    when (rdReg =/= 0.U){         //if rd is not x0 (register 0)
      regFile(rdReg) := aluResult                      //Ask if we store aluResult in rdReg what happen with ther instr
    }

  /*
   * TODO: Write result to output
   */
    io.check_res := aluResult
    PC := PC + 4.U
    stage := fetch
  }
    .otherwise 
  {

     // default case (needed for RTL-generation but should never be reached   

     assert(true.B, "Pipeline FSM must never be left")

  }
}


