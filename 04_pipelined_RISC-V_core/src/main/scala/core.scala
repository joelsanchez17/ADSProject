// ADS I Class Project
// Pipelined RISC-V Core
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 01/15/2023 by Tobias Jauch (@tojauch)

/*
The goal of this task is to extend the 5-stage multi-cycle 32-bit RISC-V core from the previous task to a pipelined processor. 
All steps and stages have the same functionality as in the multi-cycle version from task 03, but are supposed to handle different instructions in each stage simultaneously.
This design implements a pipelined RISC-V 32-bit core with five stages: IF (Fetch), ID (Decode), EX (Execute), MEM (Memory), and WB (Writeback).

    Data Types:
        The uopc enumeration data type (enum) defines micro-operation codes representing ALU operations according to the RV32I subset used in the previous tasks.

    Register File (regFile):
        The regFile module represents the register file, which has read and write ports.
        It consists of a 32-entry register file (x0 is hard-wired to zero).
        Reading from and writing to the register file is controlled by the read request (regFileReadReq), read response (regFileReadResp), and write request (regFileWriteReq) interfaces.

    Fetch Stage (IF Module):
        The IF module represents the instruction fetch stage.
        It includes an instruction memory (IMem) of size 4096 words (32-bit each).
        Instructions are loaded from a binary file (provided to the testbench as a parameter) during initialization.
        The program counter (PC) is used as an address to access the instruction memory, and one instruction is fetched in each cycle.

    Decode Stage (ID Module):
        The ID module performs instruction decoding and generates control signals.
        It extracts opcode, operands, and immediate values from the instruction.
        It uses the uopc (micro-operation code) Enum to determine the micro-operation (uop) and sets control signals accordingly.
        The register file requests are generated based on the operands in the instruction.

    Execute Stage (EX Module):
        The EX module performs the arithmetic or logic operation based on the micro-operation code.
        It takes two operands and produces the result (aluResult).

    Memory Stage (MEM Module):
        The MEM module does not perform any memory operations in this basic CPU design.

    Writeback Stage (WB Module):
        The WB module writes the result back to the register file.

    IF, ID, EX, MEM, WB Barriers:
        IFBarrier, IDBarrier, EXBarrier, MEMBarrier, and WBBarrier modules serve as pipeline registers to separate the pipeline stages.
        They hold the intermediate results of each stage until the next clock cycle.

    PipelinedRV32Icore (PipelinedRV32Icore Module):
        The top-level module that connects all the pipeline stages, barriers and the register file.
        It interfaces with the external world through check_res, which is the result produced by the core.

Overall Execution Flow:

    1) Instructions are fetched from the instruction memory in the IF stage.
    2) The fetched instruction is decoded in the ID stage, and the corresponding micro-operation code is determined.
    3) The EX stage executes the operation using the operands.
    4) The MEM stage does not perform any memory operations in this design.
    5) The result is written back to the register file in the WB stage.

Note that this design only represents a simplified RISC-V pipeline. The structure could be equipped with further instructions and extension to support a real RISC-V ISA.
*/

package core_tile

import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile


// -----------------------------------------
// Global Definitions and Data Types
// -----------------------------------------

object uopc extends ChiselEnum {

  val isADD   = Value(0x01.U)
  val isSUB   = Value(0x02.U)
  val isXOR   = Value(0x03.U)
  val isOR    = Value(0x04.U)
  val isAND   = Value(0x05.U)
  val isSLL   = Value(0x06.U)
  val isSRL   = Value(0x07.U)
  val isSRA   = Value(0x08.U)
  val isSLT   = Value(0x09.U)
  val isSLTU  = Value(0x0A.U)
  val isADDI  = Value(0x10.U)
  val invalid = Value(0xFF.U)
}

import uopc._


// -----------------------------------------
// Register File
// -----------------------------------------

class regFileReadReq extends Bundle {
    // what signals does a read request need?
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)

}

class regFileReadResp extends Bundle {
    // what signals does a read response need?
  val data1 = UInt(32.W)
  val data2 = UInt(32.W)
}

class regFileWriteReq extends Bundle {
    // what signals does a write request need?
  val rd    = UInt(5.W)
  val data  = UInt(32.W)
  val en    = Bool()

}

class regFile extends Module {
  val io = IO(new Bundle {
    val req  = Input(new regFileReadReq)
    val resp = Output(new regFileReadResp)

    /** how many read and write ports do you need to handle all requests
     from the pipeline to the register file simultaneously? */

    //Missing Bundle
    val  write = Input(new regFileWriteReq)

})
  
  /**  TODO: Initialize the register file as described in the task
          and handle the read and write requests*/

  val regFile = Mem(32, UInt(32.W))
  when(io.write.en && io.write.rd =/= 0.U) {
    regFile(io.write.rd) := io.write.data
  }

  io.resp.data1 := regFile(io.req.rs1)
  io.resp.data2 := regFile(io.req.rs2)

}

// -----------------------------------------
// Fetch Stage
// -----------------------------------------

class IF (BinaryFile: String) extends Module {
  val io = IO(new Bundle {

/** What inputs and / or outputs does this pipeline stage need? */

    val pc_out    = Output(UInt(32.W))
    val inst_out  = Output(UInt(32.W))

//    Fetch Stage (IF Module):
//      The IF module represents the instruction fetch stage.
//    It includes an instruction memory (IMem) of size 4096 words (32-bit each).
//      Instructions are loaded from a binary file (provided to the testbench as a parameter) during initialization.
//      The program counter (PC) is used as an address to access the instruction memory, and one instruction is fetched in each cycle.
  })

/** TODO: Initialize the IMEM as described in the task and handle the instruction fetch.*/

  // Program Counter
    val pc   = RegInit(0.U(32.W))

  // Instruction Memory
    val IMem = Mem(4096, UInt(32.W))
    loadMemoryFromFile(IMem, BinaryFile)

  //Fetch Instruction from Imem
    val inst = IMem(pc >> 2)

  // Outputs of the module
    io.pc_out   := pc
    io.inst_out := inst

    pc := pc + 4.U
}

// -----------------------------------------
// Decode Stage
// -----------------------------------------

class ID extends Module {
  val io = IO(new Bundle {

/** What inputs and / or outputs does this pipeline stage need? */
    val inst_in       = Input(UInt(32.W))
    val pc_in         = Input(UInt(32.W))
    val data_in       = Input(UInt(32.W))
    val rdReg_in      = Input(UInt(32.W))
    val data1_out     = Output(UInt(32.W))
    val data2_out     = Output(UInt(32.W))
    val signExt_out   = Output(UInt(32.W))
    val upo           = Output(uopc())
    val rd_out        = Output(UInt(32.W))


  })

/** TODO: Any internal signals needed? */
    val data1Reg = Reg(UInt(32.W))

//Decoding
import uopc._

    val opcode = io.inst_in(6, 0)
    val rd     = io.inst_in(11, 7)
    val funct3 = io.inst_in(14, 12)
    val rs1    = io.inst_in(19, 15)
    val rs2    = io.inst_in(24, 20)
    val funct7 = io.inst_in(31, 25)
    val immI   = io.inst_in(31, 20)

//Outputs


    io.signExt_out := Cat(Fill(20, immI(11)), immI)
    io.rd_out      := rd

 //Register File
    val rf = Module(new regFile)
    rf.io.req.rs1    := rs1
    rf.io.req.rs2    := rs2
    rf.io.write.rd   := io.rdReg_in
    rf.io.write.en   := 1.U
    rf.io.write.data := io.data_in



    data1Reg := rf.io.resp.data1

    io.data1_out := rf.io.resp.data1
    io.data2_out := rf.io.resp.data2




  /** Determine the uop based on the disassembled instruction */
  io.upo := invalid

    when(opcode === "b0110011".U){                                          // R-Type instruction
      when(funct3 === "b000".U && funct7 === "b0000000".U ){
        io.upo := isADD
//      }.elsewhere(funct3 === "b000".U && funct7 === "b0100000".U){
//        io.upo := isSUB
//      }.elsewhere(funct3Reg === "b001".U && funct7Reg === "b0000000".U){
//        io.upo := isSLL
    }
    }.elsewhen(opcode === "b0010011".U ){                                    // I-Type Instruction
      when(funct3 === "b000".U && funct7 === "b0000000".U){
        io.upo := isADDI
    }
     }
    }


//  }.elsewhen(funct3 === "b100".U) {
//    io.uopc_out := isXOR
//  }.elsewhen(funct3 === "b110".U) {
//    io.uopc_out := isOR
//  }.elsewhen(funct3 === "b111".U) {
//    io.uopc_out := isAND
//  }.elsewhen(funct3 === "b001".U) {
//    io.uopc_out := isSLL
//  }.elsewhen(funct3 === "b101".U && funct7 === "b0000000".U) {
//    io.uopc_out := isSRL
//  }.elsewhen(funct3 === "b101".U && funct7 === "b0100000".U) {
//    io.uopc_out := isSRA
//  }.elsewhen(funct3 === "b010".U) {
//    io.uopc_out := isSLT
//  }.elsewhen(funct3 === "b011".U) {
//    io.uopc_out := isSLTU
//  }
//}.elsewhen(opcode === "b0010011".U) { // I-type (e.g., ADDI)
//  when(funct3 === "b000".U) {
//    io.uopc_out := isADDI
//  }
//}
//}


// -----------------------------------------
// Execute Stage
// -----------------------------------------

class EX extends Module {
  val io = IO(new Bundle {
    val data1_in         = Input(UInt(32.W))
    val data2_in         = Input(UInt(32.W))
    val signExt_in       = Input(UInt(32.W))
    val upo_in           = Input(uopc())
    val aluResult_out    = Output(UInt(32.W))
  })

    val aluResult = Reg(UInt(32.W))
    val operandA = io.data1_in
    val operandB = Mux(io.upo_in === isADDI, io.signExt_in, io.data2_in)

import uopc._

    when( io.upo_in === isADD){
      aluResult := operandA + operandB
    }.elsewhen( io.upo_in === isADDI ){
      aluResult := operandA + operandB
    }.otherwise{
      aluResult := 0.U
    }

  io.aluResult_out := aluResult

}

// -----------------------------------------
// Memory Stage
// -----------------------------------------

class MEM extends Module {
  val io = IO(new Bundle {
    val aluResult_in  = Input(UInt(32.W))
    val aluResult_out = Output(UInt(32.W))
      })

  io.aluResult_out := io.aluResult_in
  }


// -----------------------------------------
// Writeback Stage
// -----------------------------------------

class WB extends Module {
  val io = IO(new Bundle {
    val aluResult_in     = Input(UInt(32.W))
    val aluResult_out = Output(UInt(32.W))
  })

  io.aluResult_out := io.aluResult_in

}


// -----------------------------------------
// IF-Barrier
// -----------------------------------------

class IFBarrier extends Module {
  val io = IO(new Bundle {

    val pc_in     = Input(UInt(32.W))
    val inst_in   = Input(UInt(32.W))

    val pc_out    = Output(UInt(32.W))
    val inst_out  = Output(UInt(32.W))
  })

  val pcReg = Reg(UInt(32.W))

  pcReg    := io.pc_in
  io.pc_out   := pcReg
  io.inst_out := io.inst_in

}


// -----------------------------------------
// ID-Barrier
// -----------------------------------------

class IDBarrier extends Module {
  val io = IO(new Bundle {

    val data1_in   = Input(UInt(32.W))
    val data2_in   = Input(UInt(32.W))
    val signExt_in = Input(UInt(32.W))
    val upo_in     = Input(uopc())
    val rd_in      = Input(UInt(32.W))
//    val rs1_in     = Input(UInt(32.W))
//    val rs2_in     = Input(UInt(32.W))


    val data1_out   = Output(UInt(32.W))
    val data2_out   = Output(UInt(32.W))
    val signExt_out = Output(UInt(32.W))
    val upo_out     = Output(uopc())
    val rd_out      = Output(UInt(32.W))
//    val rs1_out     = Output(UInt(32.W))
//    val rs2_out     = Output(UInt(32.W))



  })

    val data1Reg   = Reg(UInt(32.W))
    val data2Reg   = Reg(UInt(32.W))
    val signExtReg = Reg(UInt(32.W))
    val upoReg     = Reg(uopc())
    val rdReg      = Reg(UInt(32.W))
//    val rs1Reg     = Reg(UInt(32.W))
//    val rs2Reg     = Reg(UInt(32.W))


    data1Reg   := io.data1_in
    data2Reg   := io.data2_in
    signExtReg := io.signExt_in
    upoReg     := io.upo_in
    rdReg      := io.rd_in
    dontTouch(rdReg)
//    rs1Reg     := io.rs1_in
//    rs2Reg     := io.rs2_in


    io.data1_out   := data1Reg
    io.data2_out   := data2Reg
    io.signExt_out := signExtReg
    io.upo_out     := upoReg
    io.rd_out      := rdReg

//    io.rs1_out     := rs1Reg
//    io.rs2_out     := rs2Reg


}


// -----------------------------------------
// EX-Barrier
// -----------------------------------------

class EXBarrier extends Module {
  val io = IO(new Bundle {

    val aluResult_in  = Input(UInt(32.W))
    val aluResult_out = Output(UInt(32.W))

  })
    val aluReg = Reg(UInt(32.W))

    aluReg := io.aluResult_in
    io.aluResult_out  := aluReg
}


// -----------------------------------------
// MEM-Barrier
// -----------------------------------------

class MEMBarrier extends Module {
  val io = IO(new Bundle {
    val aluResult_in  = Input(UInt(32.W))
    val aluResult_out = Output(UInt(32.W))
  })

    io.aluResult_out  := io.aluResult_in
}


// -----------------------------------------
// WB-Barrier
// -----------------------------------------

class WBBarrier extends Module {
  val io = IO(new Bundle {
    val aluResult_in  = Input(UInt(32.W))
    val aluResult_out = Output(UInt(32.W))
  })

    io.aluResult_out  := io.aluResult_in

}

class PipelinedRV32Icore (BinaryFile: String) extends Module {
  val io = IO(new Bundle {
    val check_res = Output(UInt(32.W))
  })

  /** Initiation stages */
  val ifStage  = Module(new IF (BinaryFile: String))
  val idStage  = Module(new ID)
  val exStage  = Module(new EX)
  val memStage = Module(new MEM)
  val wbStage  = Module(new WB)

  /** Initiation Barriers */
  val ifBarrier  = Module(new IFBarrier)
  val idBarrier  = Module(new IDBarrier)
  val exBarrier  = Module(new EXBarrier)
  val memBarrier = Module(new MEMBarrier)
  val wbBarrier  = Module(new WBBarrier)


  /** TODO: Instantiate Pipeline Stages */

  /** IF -> IFBarrier    */
  ifBarrier.io.pc_in   := ifStage.io.pc_out
  ifBarrier.io.inst_in := ifStage.io.inst_out

  /** IFBarrier -> ID    */
  idStage.io.pc_in     := ifBarrier.io.pc_out
  idStage.io.inst_in   := ifBarrier.io.inst_out
  idStage.io.data_in   := exStage.io.aluResult_out
  idStage.io.rdReg_in  := idBarrier.io.rd_out

  /** ID -> IDBarrier    */
  idBarrier.io.data1_in    := idStage.io.data1_out
  idBarrier.io.data2_in    := idStage.io.data2_out
  idBarrier.io.signExt_in  := idStage.io.signExt_out
  idBarrier.io.upo_in      := idStage.io.upo

  // Not sure if needed
//  idBarrier.io.rs1_in      := idStage.io.rs1_out
//  idBarrier.io.rs2_in      := idStage.io.rs2_out
  idBarrier.io.rd_in       := idStage.io.rd_out

  /** IDBarrier -> EX */
  exStage.io.data1_in   := idBarrier.io.data1_out
  exStage.io.data2_in   := idBarrier.io.data2_out
  exStage.io.signExt_in := idBarrier.io.signExt_out
  exStage.io.upo_in     := idBarrier.io.upo_out


  /** EX -> EXBarrier */
   exBarrier.io.aluResult_in  := exStage.io.aluResult_out

  /** EXBarrier -> MEM */
   memStage.io.aluResult_in   := exBarrier.io.aluResult_out

  /** MEM -> MEMBarrier */
   memBarrier.io.aluResult_in := memStage.io.aluResult_out

  /** MEMBarrier -> WB */
   wbStage.io.aluResult_in    := memBarrier.io.aluResult_out

  /** WB -> WBBarrier */
  wbBarrier.io.aluResult_in   := wbStage.io.aluResult_out

  /** TODO: Instantiate Register Files */

  io.check_res := wbBarrier.io.aluResult_out


}

