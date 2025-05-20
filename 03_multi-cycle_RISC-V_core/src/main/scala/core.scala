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
    val check_res = Output(SInt(32.W))
  })

  val fetch :: decode :: execute :: memory :: writeback :: Nil = Enum(5) // Enum datatype to define the stages of the processor FSM
  val stage = RegInit(fetch) 

  // -----------------------------------------
  // Instruction Memory
  // -----------------------------------------

  /** TODO: Implement the memory as described above */
  val IMem = Mem(4096, UInt(32.W))       // Creation of block-memory of 32 bits which can hold 4062 values
    loadMemoryFromFile(IMem, BinaryFile)   // Load File in Memory for initiation


  // Directly write instruction in memory
  //IMem.write(0.U, "h00500093".U)  // addi x1, x0, 5


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
  val rdReg = RegInit(0.U(5.W))
  val rs1Reg = Reg(SInt(5.W))
  val rs2Reg = Reg(SInt(5.W))


  /**  EX/MEM Execute to Memory Access */

  val operandA = Reg(SInt(32.W))
  val operandB = Reg(SInt(32.W))

  /**  EX/MEM Memory Access to WriteBack */
  val aluResult = RegInit(0.S(32.W))

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
  io.check_res := 0.S


  // -----------------------------------------
  // Processor Stages
  // -----------------------------------------

  when (stage === fetch)
  {
  /** TODO: Implement fetch stage */
    val inst = Wire(UInt(32.W))
    inst := IMem(PC >> 2.U)
    instReg := inst
    stage:= decode
  } 
    .elsewhen (stage === decode)
  {
    /** TODO: code stage */

    val funct3 = Wire(UInt(3.W))
    val funct7 = Wire(UInt(7.W))
    val opcode = Wire(UInt(7.W))

    val immI = Wire(UInt(16.W))
    val immI_sext = Wire(UInt(32.W))


    val rd  = Wire(UInt(5.W))
    val rs1 = Wire(UInt(5.W))
    val rs2 = Wire(UInt(5.W))

    opcode := instReg(6, 0)
    rd := instReg(11, 7)
    funct3 := instReg(14, 12)
    rs1 := instReg(19, 15)
    rs2 := instReg(24, 20)
    funct7 := instReg(31, 25)

    rdReg :=  rd

    immI := instReg(31, 20)
    immI_sext := Cat(Fill(21, immI(11)), immI)

    /** Functions */
    val isADD_wire = (opcode === "b0110011".U && funct3 === "b000".U && funct7 === "b0000000".U)
    isADD := isADD_wire
    dontTouch(isADD_wire)


    val isADDI_wire = (opcode === "b0010011".U && funct3 === "b000".U)
    isADDI := isADDI_wire

    val isSUB_wire  = (opcode === "b0110011".U && funct3 === "b000".U && funct7 === "b0100000".U)
    isSUB := isSUB_wire
//    // SLL Logical left shift rd = rs1 << (rs2 & 0x1F)
//    isSLL  := (opcodeReg === "b0110011".U && funct3Reg === "b001".U && funct7Reg === "b0000000".U)
//    // SLT set if less than (signed) rd = (rs1 < rs2)? 1 : 0
//    isSLT  := (opcodeReg === "b0110011".U && funct3Reg === "b010".U && funct7Reg === "b0000000".U)
//    // SLTU set if less than (unsigned) rd = (rs1 < rs2)? 1:0
//    isSLTU := (opcodeReg === "b0110011".U && funct3Reg === "b011".U && funct7Reg === "b0000000".U)
//    // XOR rd = rs1 ^ rs2 (only one set to 1)
//    isXOR  := (opcodeReg === "b0110011".U && funct3Reg === "b100".U && funct7Reg === "b0000000".U)
//    // SRL Logical right shift rd = rs1 >> (rs2 & 0x1F)
//    isSRL  := (opcodeReg === "b0110011".U && funct3Reg === "b101".U && funct7Reg === "b0000000".U)
//    // SRA Arithmetic right shift: preserves sign rd = rs1 >>> (rs & 0x1F)
//    isSRA  := (opcodeReg === "b0110011".U && funct3Reg === "b101".U && funct7Reg === "b0100000".U)
//    isOR   := (opcodeReg === "b0110011".U && funct3Reg === "b110".U && funct7Reg === "b0000000".U)
    val isAND_wire  = (opcode === "b0110011".U && funct3 === "b111".U && funct7 === "b0000000".U)
    isAND := isAND_wire

/**    val A_test = Wire(Bool())
       val B_test = Wire(Bool())

      dontTouch(A_test)
      dontTouch(B_test)

      when (opcode === "b0010011".U) {
      A_test := true.B
      } .otherwise {
        A_test := false.B
      }

      when (funct3 === "b000".U) {
        B_test := true.B
      } .otherwise {
        B_test := false.B
      }

      isADDI := A_test && B_test
      val isADDI_wire = A_test && B_test */

    val rs1Data = Wire(SInt(32.W))
    val rs2Data = Wire(SInt(32.W))

    rs1Data := regFile(rs1.asUInt).asSInt
    rs2Data := Mux(isADDI_wire, immI_sext.asSInt, regFile(rs2.asUInt).asSInt)

    val operandA_wire = Wire(SInt(32.W))
    operandA_wire := rs1Data


    operandA := operandA_wire
    operandB := rs2Data

    stage := execute
  }
    .elsewhen (stage === execute)
  {
    when(isADDI) {
      aluResult := operandA + operandB
    }.elsewhen(isADD) {
      aluResult := operandA + operandB
    }.elsewhen(isSUB) {
      aluResult := operandA - operandB
//    }.elsewhen(isSLL) {
//      aluResult := operandA << operandB(4, 0)                          //operandA shifted left by operandB
//    }.elsewhen(isSLT) {
//      aluResult := Mux(operandA.asSInt < operandB.asSInt, 1.U, 0.U)    //if true 1, otherwise 0
//    }.elsewhen(isSLTU) {
//      aluResult := Mux(operandA < operandB, 1.U, 0.U)                  //if true 1, otherwise 0
//    }.elsewhen(isXOR) {
//      aluResult := operandA ^ operandB
//    }.elsewhen(isSRL) {
//      aluResult := operandA >> operandB(4, 0)
//    }.elsewhen(isSRA) {
//      aluResult := (operandA.asSInt() >> operandB(4,0)).asUInt
//    }.elsewhen(isOR) {
//      aluResult := operandA | operandB
//    }.elsewhen(isAND) {
//      aluResult := operandA & operandB
    }.otherwise {
      aluResult := 0.S                                                 // Default case for unimplemented instructions
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
      regFile(rdReg.asUInt) := aluResult.asUInt                      //Ask if we store aluResult in rdReg what happen with ther instr
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


