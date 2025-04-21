// ADS I Class Project testing!
// Single-Cycle RISC-V Core
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 05/10/2023 by Tobias Jauch (@tojauch)

/*
The goal of this task is to implement a simple 32-bit RISC-V processor supporting parts of the RV32I instruction set architecture. This RV32I core is relatively basic 
and does not include features like memory operations, exception handling, or branch instructions. It is designed for a simplified subset of the RISC-V ISA. It mainly 
focuses on ALU operations and basic instruction execution. 

    Instruction Memory:
        The code initializes an instruction memory "mem" with a capacity of 4096 32-bit words.
        It loads the content of this memory from a binary file specified by the BinaryFile parameter.

    CPU Registers:
        The code initializes a program counter (PC) register "PC" with an initial value of 0.
        The core contains a register file "regFile" with 32 entries. Register x0 is hard-wired to always contain the value 0.

    Fetch Stage:
        The Fetch Stage reads the current instruction from "mem" based on the value of the program counter PC.

    Decode Stage:
        This stage extracts various fields (opcode, rd, funct3, rs1, funct7, and rs2) from the fetched instruction, according to the RISC-V ISA specification. 
        The core determines the operands for ALU operations based on the instruction type and stores them into two variables "operandA" and "operandB". 
        For R-Type instructions, both operands are read from regFile. For I-Type instructions, operandA is read from regFile, while operandB is an 
        immediate value, encoded in the instruction.

        The code defines a set of boolean signals (isADD, isADDI, etc.) to identify the type of instruction based on the opcode and function fields. These 
        signals are then used to determine the operation to be performed in the Execute Stage.

    Execute Stage:
        In the Execute Stage, ALU operations are performed based on the instruction type and the operands. The result is stored in "aluResult".

    Memory Stage:
        This core does not include memory operations, leave this section empty.

    Write Back Stage:
        The result of the ALU operation ("aluResult") is propagated to "writeBackData".
        The code writes the value contained ba "writeBackData" to the destination register in the regFile specified by "rd".

    Check Result Signal:
        The result of the ALU operation is also provided as output to "io.check_res". This signal will be used by our test cases to validate the actions performed by our core.

    Update PC:
        The program counter PC is incremented by 4 in every cycle, as this core does not include jumps or branches. It simply fetches instructions sequentially.
*/

package core_tile

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile

class RV32Icore (BinaryFile: String) extends Module {
  val io = IO(new Bundle {
    val check_res = Output(UInt(32.W))
  })


  // -----------------------------------------
  // Instruction Memory
  // -----------------------------------------

  val IMem = Mem(4096, UInt(32.W))       // Creation of block-memory of 32 bits which can hold 4062 values
  loadMemoryFromFile(IMem, BinaryFile)   // Load File in Memory for initiation

  // -----------------------------------------
  // CPU Registers
  // -----------------------------------------

  /*
   * TODO: Implement the program counter as a register, initialize with zero
   */
  val PC= RegInit(0.U(32W))          // Definition of register with init value 0

  val regFile = Mem(32, UInt(32.W))  // Creation of the Block-Register File 32 register with 32 bits each of them
  /*
   * TODO: hard-wire register x0 to zero
   */

  // -----------------------------------------
  // Fetch
  // -----------------------------------------

  val instr  = Wire(UInt(32.W))    // Connection between different part of the circuit IMem > RegFile
  instr := IMem(PC>>2.U)           // Take the instruction located in PC(entry address) with shift of 2 bits
                                   // Now instr take the instruction of 32 bits and have to be decode

  // -----------------------------------------
  // Decode
  // -----------------------------------------

  val opcode = instr(6, 0)    // Operation to be perform (add, subtract, load from memory, etc)

  /* TODO: Add missing fields from fetched instructions for decoding */

  val rd = instr(7, 11)       // Destination register
  val funct3 = instr(12, 14)  // Shift right or left
  val rs1 = instr(15, 19)     // Source register 1
  val rs2 = instr(20, 24)     // Source register 2
  val funct7 = instr(25, 31)  // Complement of opcode (help to determine exactly what operation to do)

  // I-type

  val immI = instr(31, 20)
  val immI_sext = Cat(Fill(20, immI(11)), immI)  // transfor immI (12 bits) into a signal of 32 bits

  // Functions

  val isADD  = (opcode === "b0110011".U && funct3 === "b000".U && funct7 === "b0000000".U)

  /* TODO: Add missing R-Type instructions here  */

  val isSUB  = (opcode === "b0110011".U && funct3 === "b000".U && funct7 === "b0100000".U)
  // SLL Logical left shift rd = rs1 << (rs2 & 0x1F)
  val isSLL  = (opcode === "b0110011".U && funct3 === "b001".U && funct7 === "b0000000".U)
  // SLT set if less than (signed) rd = (rs1 < rs2)? 1 : 0
  val isSLT  = (opcode === "b0110011".U && funct3 === "b010".U && funct7 === "b0000000".U)
  // SLTU set if less than (unsigned) rd = (rs1 < rs2)? 1:0
  val isSLTU = (opcode === "b0110011".U && funct3 === "b011".U && funct7 === "b0000000".U)
  // XOR rd = rs1 ^ rs2 (only one set to 1)
  val isXOR  = (opcode === "b0110011".U && funct3 === "b100".U && funct7 === "b0000000".U)
  // SRL Logical right shift rd = rs1 >> (rs2 & 0x1F)
  val isSRL  = (opcode === "b0110011".U && funct3 === "b101".U && funct7 === "b0000000".U)
  // SRA Arithmetic right shift: preserves sign rd = rs1 >>> (rs & 0x1F)
  val isSRA  = (opcode === "b0110011".U && funct3 === "b101".U && funct7 === "b0100000".U)
  val isOR   = (opcode === "b0110011".U && funct3 === "b110".U && funct7 === "b0100000".U)
  val isAND  = (opcode === "b0110011".U && funct3 === "b111".U && funct7 === "b0100000".U)

  val isADDI = (opcode === "b0010011".U && funct3 === "b000".U)

  // Operands

   /* TODO: Add operand signals according to specification */

    val operandA = Wire(UInt(32.W))
    val operandB = Wire(UInt(32.W))

    operandA := regFile(rs1)

    //For R-type, operandB comes from rs2, for I-Type, it is the immediate

    operandB := Mux(isADDI, immI_sext, regFile(rs2)) //if ADDI true, then immI_sext, otherwise regFile(rs2)

  // -----------------------------------------
  // Execute
  // -----------------------------------------

  val aluResult = Wire(UInt(32.W)) 

  when(isADDI) { 
    aluResult := operandA + operandB 
  }.elsewhen(isADD) {                           
    aluResult := operandA + operandB 
  }.elsewhen(isSUB) {
      aluResult := operandA - operandB
    }.elsewhen(isSLL) {
    aluResult := operandA << operandB                                 //operandA shifted left by operandB
  }.elsewhen(isSLT) {
    aluResult := Mux(operandA.asSInt < operandB.asSInt, 1.U, 0.U)     //if true 1, otherwise 0
  }.elsewhen(isSLTU) {
    aluResult := Mux(operandA < operandB, 1.U, 0.U)                   //if true 1, otherwise 0
  }.elsewhen(isXOR) {
    aluResult := operandA ^ operandB
  }.elsewhen(isSRL) {
    aluResult := operandA >> operandB
  }.elsewhen(isSRA) {
    aluResult := Mux(operandA.asSInt > operandB.asSInt, 1.U, 0.U)     //if true 1, otherwise 0
  }.elsewhen(isOR) {
    aluResult := operandA | operandB
  }.elsewhen(isAND) {
    aluResult := operandA & operandB
  }.otherwise {
    aluResult := 0.U            // Default case for unimplemented instructions
  }
  /* TODO: Add missing R-Type instructions here. Do not forget to implement a suitable default case for
           fetched instructions that are neither R-Type nor ADDI.  */

  // -----------------------------------------
  // Memory
  // -----------------------------------------

  // No memory operations implemented in this basic CPU


  // -----------------------------------------
  // Write Back 
  // -----------------------------------------

  val writeBackData = Wire(UInt(32.W)) 
  writeBackData := aluResult

  /* TODO: Store "writeBackData" in register "rd" in regFile */
  when (rd =/= 0.U){         //if rd is not x0 (register 0)
    regFile(rd := writeBackData)
  }

  // Check Result
  /*
   * TODO: Propagate "writeBackData" to the "check_res" output for testing purposes
   */
  io.check_res := 0.U

  // Update PC
  // no jumps or branches, next PC always reads next address from IMEM

  PC := PC + 4.U



   * TODO: Increment PC
   */

}
