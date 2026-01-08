// ADS I Class Project
// Pipelined RISC-V Core with Hazard Detetcion and Resolution
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 05/21/2024 by Andro Mazmishvili (@Andrew8846)

/*
The goal of this task is to equip the pipelined 5-stage 32-bit RISC-V core from the previous task with a forwarding unit that takes care of hazard detetction and hazard resolution.
The functionality is the same as in task 4, but the core should now also be able to also process instructions with operands depending on the outcome of a previous instruction without stalling.

In addition to the pipelined design from task 4, you need to implement the following modules and functionality:

    Hazard Detection and Forwarding:
        Forwarding Unit: Determines if and from where data should be forwarded to resolve hazards.
                         Resolves data hazards by forwarding the correct values from later pipeline stages to earlier ones.
                         - Inputs: Register identifiers from the ID, EX, MEM, and WB stages.
                         - Outputs: Forwarding select signals (forwardA and forwardB) indicating where to forward the values from.

        The forwarding logic utilizes multiplexers to select the correct operand values based on forwarding decisions.

Make sure that data hazards (dependencies between instructions in the pipeline) are detected and resolved without stalling the pipeline. For additional information, you can revise the ADS I lecture slides (6-25ff).

Note this design only represents a simplified RISC-V pipeline. The structure could be equipped with further instructions and extension to support a real RISC-V ISA.
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
    val addr  = Input(UInt(5.W))
}

class regFileReadResp extends Bundle {
    val data  = Output(UInt(32.W))
}

class regFileWriteReq extends Bundle {
    val addr  = Input(UInt(5.W))  // rd
    val data  = Input(UInt(32.W))
    val wr_en = Input(Bool())
}

class regFile extends Module {
  val io = IO(new Bundle {
    val req_1  = new regFileReadReq    // rs1
    val resp_1 = new regFileReadResp   // data1
    val req_2  = new regFileReadReq    // rs2
    val resp_2 = new regFileReadResp   // data2
    val req_3  = new regFileWriteReq   // writeBack 3 Wires (rd, data, en)
})

  val regFile = Reg(Vec(32, UInt(32.W)))
  for (i <- 0 until 32) { dontTouch(regFile(i)) }
  regFile(0) := 0.U                           // hard-wired zero for x0




  when(io.req_3.wr_en){
    when(io.req_3.addr =/= 0.U){
      regFile(io.req_3.addr) := io.req_3.data
    }
  }

  io.resp_1.data := Mux(io.req_1.addr === 0.U, 0.U, regFile(io.req_1.addr))
  io.resp_2.data := Mux(io.req_2.addr === 0.U, 0.U, regFile(io.req_2.addr))

  when(io.req_3.wr_en) {
    when(io.req_3.addr === io.req_1.addr) {
      io.resp_1.data := io.req_3.data
    }
    when(io.req_3.addr === io.req_2.addr) {
      io.resp_2.data := io.req_3.data
    }
  }
  dontTouch(io.req_3.addr)
  dontTouch(io.req_3.data)
  dontTouch(io.req_3.wr_en)



}

class ForwardingUnit extends Module {
  val io = IO(new Bundle {
    val inRS1    = Input(UInt(5.W))
    val inRS2    = Input(UInt(5.W))
    val inRD_mem = Input(UInt(5.W))
    val inRD_wb  = Input(UInt(5.W))
//    val RD_wb_2 = Input(UInt(5.W))
    val forwardA = Output(UInt(2.W))
    val forwardB = Output(UInt(2.W))


  })

    val forwardA = WireDefault(0.U(2.W))
    val forwardB = WireDefault(0.U(2.W))



  /**Hazard detetction logic and Forwarding Selection*/

   //Forwarding logic for RS1

    when(io.inRD_mem =/= 0.U && io.inRD_mem === io.inRS1){
      forwardA := 1.U
    }.elsewhen(io.inRD_wb =/= 0.U && io.inRD_wb ===io.inRS1){
      forwardA := 2.U
//    }.elsewhen(io.inRD_wb =/= 0.U && io.RD_wb_2 === io.inRS1){
//      forwardA := 3.U
    }

  //Forwarding logic for RS2

  when(io.inRD_mem =/= 0.U && io.inRD_mem === io.inRS2){
    forwardB := 1.U
  }.elsewhen(io.inRD_wb =/= 0.U && io.inRD_wb === io.inRS2){
    forwardB := 2.U
//  }.elsewhen(io.inRD_wb =/= 0.U && io.RD_wb_2 === io.inRS2){
//    forwardB := 3.U
  }

    io.forwardA := forwardA
    io.forwardB := forwardB
}


// -----------------------------------------
// Fetch Stage
// -----------------------------------------

class IF (BinaryFile: String) extends Module {
  val io = IO(new Bundle {
    val instr = Output(UInt(32.W))
    val pc    = Output(UInt(32.W))
  })

  val IMem = Mem(4096, UInt(32.W))
  loadMemoryFromFile(IMem, BinaryFile)



  val PC = RegInit(0.U(32.W))

  io.instr := IMem(PC>>2.U)

  // Update PC
  // no jumps or branches, next PC always reads next address from IMEM
  PC := PC + 4.U
  io.pc := PC

}

// -----------------------------------------
// Decode Stage
// -----------------------------------------

class ID extends Module {
  val io = IO(new Bundle {
    val regFileReq_A  = Flipped(new regFileReadReq)
    val regFileResp_A = Flipped(new regFileReadResp)
    val regFileReq_B  = Flipped(new regFileReadReq)
    val regFileResp_B = Flipped(new regFileReadResp)
    val instr         = Input(UInt(32.W))
    val uop           = Output(uopc())
    val rd            = Output(UInt(5.W))
    val rs1           = Output(UInt(5.W))
    val rs2           = Output(UInt(5.W))
    val operandA      = Output(UInt(32.W))
    val operandB      = Output(UInt(32.W))
  })

  val opcode  = io.instr(6, 0)
  io.rd      := io.instr(11, 7)
  val funct3  = io.instr(14, 12)
  val rs1     = io.instr(19, 15)

  // R-Type
  val funct7  = io.instr(31, 25)
  val rs2     = io.instr(24, 20)

  // I-Type
  val imm     = io.instr(31, 20)

  when(opcode === "b0110011".U){
    when(funct3 === "b000".U){
      when(funct7 === "b0000000".U){
        io.uop := isADD
      }.elsewhen(funct7 === "b0100000".U){
        io.uop := isSUB
      }.otherwise{
        io.uop := invalid
      }
    }.elsewhen(funct3 === "b100".U){
      when(funct7 === "b0100000".U){
        io.uop := isXOR
      }.otherwise{
        io.uop := invalid
      }
    }.elsewhen(funct3 === "b110".U){
      when(funct7 === "b0000000".U){
        io.uop := isOR
      }.otherwise{
        io.uop := invalid
      }
    }.elsewhen(funct3 === "b111".U){
      when(funct7 === "b0000000".U){
        io.uop := isAND
      }.otherwise{
        io.uop := invalid
      }
    }.elsewhen(funct3 === "b001".U){
      when(funct7 === "b0000000".U){
        io.uop := isSLL
      }.otherwise{
        io.uop := invalid
      }
    }.elsewhen(funct3 === "b101".U){
      when(funct7 === "b0000000".U){
        io.uop := isSRL
      }.elsewhen(funct7 === "b0100000".U){
        io.uop := isSRA
      }.otherwise{
        io.uop := invalid
      }
    }.elsewhen(funct3 === "b010".U){
      when(funct7 === "b0000000".U){
        io.uop := isSLT
      }.otherwise{
        io.uop := invalid
      }
    }.elsewhen(funct3 === "b011".U){
      when(funct7 === "b0000000".U){
        io.uop := isSLTU
      }.otherwise{
        io.uop := invalid
      }
    }.otherwise{
      io.uop := invalid
    }
  }.elsewhen(opcode === "b0010011".U){
    when(funct3 === "b000".U){
      io.uop := isADDI
    }.otherwise{
      io.uop := invalid
    }
  }.otherwise{
    io.uop := invalid
  }

  // Operands
  io.regFileReq_A.addr := rs1
  io.regFileReq_B.addr := rs2

  io.operandA := io.regFileResp_A.data
  io.operandB := Mux(opcode === "b0110011".U, io.regFileResp_B.data, Mux(opcode === "b0010011".U, imm, 0.U))

  io.rs1     := rs1
  io.rs2     := rs2

}

// -----------------------------------------
// Execute Stage
// -----------------------------------------

class EX extends Module {
  val io = IO(new Bundle {
    val uop       = Input(uopc())
    val operandA  = Input(UInt(32.W))
    val operandB  = Input(UInt(32.W))
    val aluResult = Output(UInt(32.W))
  })

  val operandA = io.operandA
  val operandB = io.operandB
  val uop      = io.uop

  when(uop === isADDI) {
      io.aluResult := operandA + operandB
    }.elsewhen(uop === isADD) {
      io.aluResult := operandA + operandB
    }.elsewhen(uop === isSUB) {
      io.aluResult := operandA - operandB
    }.elsewhen(uop === isXOR) {
      io.aluResult := operandA ^ operandB
    }.elsewhen(uop === isOR) {
      io.aluResult := operandA | operandB
    }.elsewhen(uop === isAND) {
      io.aluResult := operandA & operandB
    }.elsewhen(uop === isSLL) {
      io.aluResult := operandA << operandB(4, 0)
    }.elsewhen(uop === isSRL) {
      io.aluResult := operandA >> operandB(4, 0)
    }.elsewhen(uop === isSRA) {
      io.aluResult := operandA >> operandB(4, 0)          // automatic sign extension, if SInt datatype is used
    }.elsewhen(uop === isSLT) {
      io.aluResult := Mux(operandA < operandB, 1.U, 0.U)  // automatic sign extension, if SInt datatype is used
    }.elsewhen(uop === isSLTU) {
      io.aluResult := Mux(operandA < operandB, 1.U, 0.U)
    }.otherwise{
      io.aluResult := "h_FFFF_FFFF".U // = 2^32 - 1; self-defined encoding for invalid operation, value is unlikely to be reached in a regular arithmetic operation
    }

}

// -----------------------------------------
// Memory Stage
// -----------------------------------------

class MEM extends Module {
  val io = IO(new Bundle {

  })

  // No memory operations implemented in this basic CPU

}

// -----------------------------------------
// Writeback Stage
// -----------------------------------------

class WB extends Module {
  val io = IO(new Bundle {
    val regFileReq = Flipped(new regFileWriteReq)
    val rd         = Input(UInt(5.W))
    val aluResult  = Input(UInt(32.W))
    val check_res  = Output(UInt(32.W))
  })

 io.regFileReq.addr  := io.rd
 io.regFileReq.data  := io.aluResult
 io.regFileReq.wr_en := io.aluResult =/= "h_FFFF_FFFF".U  // could depend on the current uopc, if ISA is extendet beyond R-type and I-type instructions

 io.check_res := io.aluResult

}


// -----------------------------------------
// IF-Barrier
// -----------------------------------------

class IFBarrier extends Module {
  val io = IO(new Bundle {
    val inst_in  = Input(UInt(32.W))
    val inst_out = Output(UInt(32.W))
    val pc_in  = Input(UInt(32.W))
    val pc_out = Output(UInt(32.W))
  })

//  val instrReg = RegInit(0.U(32.W))
//  val pcReg    = RegInit(0.U(32.W))

//  io.inst_out:= instrReg
//  instrReg    := io.inst_in

  io.pc_out := io.pc_in
  io.inst_out := io.inst_in



}


// -----------------------------------------
// ID-Barrier
// -----------------------------------------

class IDBarrier extends Module {
  val io = IO(new Bundle {
    val inUOP       = Input(uopc())
    val inRD        = Input(UInt(5.W))
    val inRS1       = Input(UInt(5.W))
    val inRS2       = Input(UInt(5.W))
    val inOperandA  = Input(UInt(32.W))
    val inOperandB  = Input(UInt(32.W))
    val outUOP      = Output(uopc())
    val outRD       = Output(UInt(5.W))
    val outRS1      = Output(UInt(5.W))
    val outRS2      = Output(UInt(5.W))
    val outOperandA = Output(UInt(32.W))
    val outOperandB = Output(UInt(32.W))
    val pc_in  = Input(UInt(32.W))
    val pc_out = Output(UInt(32.W))
    val inst_in  = Input(UInt(32.W))
    val inst_out = Output(UInt(32.W))
  })

  val uop      = Reg(uopc())
  val rd       = RegInit(0.U(5.W))
  val rs1      = RegInit(0.U(5.W))
  val rs2      = RegInit(0.U(5.W))
  val operandA = RegInit(0.U(32.W))
  val operandB = RegInit(0.U(32.W))
  val pcReg    = RegInit(0.U(32.W))
  val instReg    = RegInit(0.U(32.W))


  io.outUOP := uop
  uop := io.inUOP
  io.outRD := rd
  rd := io.inRD
  io.outRS1 := rs1
  rs1 := io.inRS1
  io.outRS2 := rs2
  rs2 := io.inRS2
  io.outOperandA := operandA
  operandA := io.inOperandA
  io.outOperandB := operandB
  operandB := io.inOperandB
  pcReg := io.pc_in
  io.pc_out := pcReg
  dontTouch(pcReg)

  instReg := io.inst_in
  io.inst_out := instReg
  dontTouch(instReg)



}

// -----------------------------------------
// EX-Barrier
// -----------------------------------------

class EXBarrier extends Module {
  val io = IO(new Bundle {
    val inAluResult  = Input(UInt(32.W))
    val outAluResult = Output(UInt(32.W))
    val inRD         = Input(UInt(5.W))
    val outRD        = Output(UInt(5.W))
    val pc_in  = Input(UInt(32.W))
    val pc_out = Output(UInt(32.W))
    val inst_in  = Input(UInt(32.W))
    val inst_out = Output(UInt(32.W))
  })

  val aluResult = RegInit(0.U(32.W))
  val rd       = RegInit(0.U(5.W))
  val pcReg    = RegInit(0.U(32.W))
  val instReg    = RegInit(0.U(32.W))


  io.outAluResult := aluResult
  aluResult       := io.inAluResult

  io.outRD := rd
  rd := io.inRD


  pcReg := io.pc_in
  io.pc_out := pcReg
  dontTouch(pcReg)

  instReg := io.inst_in
  io.inst_out := instReg
  dontTouch(instReg)
}


// -----------------------------------------
// MEM-Barrier
// -----------------------------------------

class MEMBarrier extends Module {
  val io = IO(new Bundle {
    val inAluResult  = Input(UInt(32.W))
    val outAluResult = Output(UInt(32.W))
    val inRD         = Input(UInt(5.W))
    val outRD        = Output(UInt(5.W))
    val pc_in  = Input(UInt(32.W))
    val pc_out = Output(UInt(32.W))
    val inst_in  = Input(UInt(32.W))
    val inst_out = Output(UInt(32.W))
  })

  val aluResult = RegInit(0.U(32.W))
  val rd        = RegInit(0.U(5.W))
  val pcReg    = RegInit(0.U(32.W))
  val instReg    = RegInit(0.U(32.W))

  io.outAluResult := aluResult
  aluResult       := io.inAluResult

  io.outRD := rd
  rd := io.inRD

  pcReg := io.pc_in
  io.pc_out := pcReg
  dontTouch(pcReg)

  instReg := io.inst_in
  io.inst_out := instReg
  dontTouch(instReg)
}


// -----------------------------------------
// WB-Barrier
// -----------------------------------------

class WBBarrier extends Module {
  val io = IO(new Bundle {
    val inCheckRes   = Input(UInt(32.W))
    val outCheckRes  = Output(UInt(32.W))
    val inRD         = Input(UInt(5.W))
    val outRD        = Output(UInt(5.W))
    val pc_in  = Input(UInt(32.W))
    val pc_out = Output(UInt(32.W))
    val inst_in  = Input(UInt(32.W))
    val inst_out = Output(UInt(32.W))
  })

  val check_res   = RegInit(0.U(32.W))
  val rd          = RegInit(0.U(5.W))
  val pcReg    = RegInit(0.U(32.W))
  val instReg    = RegInit(0.U(32.W))

  io.outRD := rd
  rd := io.inRD

  pcReg := io.pc_in
  io.pc_out := pcReg
  dontTouch(pcReg)

  instReg := io.inst_in
  io.inst_out := instReg
  dontTouch(instReg)

  io.outCheckRes := check_res
  check_res      := io.inCheckRes
}


// -----------------------------------------
// Main Class
// -----------------------------------------

class HazardDetectionRV32Icore (BinaryFile: String) extends Module {
  val io = IO(new Bundle {
    val check_res = Output(UInt(32.W))
  })
  val pc_if    = RegInit(0.U(32.W))
  val pc_id    = RegInit(0.U(32.W))
  val pc_ex    = RegInit(0.U(32.W))
  val pc_mem   = RegInit(0.U(32.W))
  val pc_wb    = RegInit(0.U(32.W))

  dontTouch(pc_if)
  dontTouch(pc_id)
  dontTouch(pc_ex)
  dontTouch(pc_mem)
  dontTouch(pc_wb)



  // Pipeline Registers
  val IFBarrier  = Module(new IFBarrier)
  val IDBarrier  = Module(new IDBarrier)
  val EXBarrier  = Module(new EXBarrier)
  val MEMBarrier = Module(new MEMBarrier)
  val WBBarrier  = Module(new WBBarrier)

  // Pipeline Stages
  val IF  = Module(new IF(BinaryFile))
  val ID  = Module(new ID)
  val EX  = Module(new EX)
  val MEM = Module(new MEM)
  val WB  = Module(new WB)

  /** TODO: Instantiate the forwarding unit */
  val FU = Module(new ForwardingUnit)


  //Register File
  val regFile = Module(new regFile)

  // Connections for IOs
  IFBarrier.io.inst_in     := IF.io.instr
  IFBarrier.io.pc_in        := IF.io.pc

  ID.io.instr               := IFBarrier.io.inst_out
  ID.io.regFileReq_A        <> regFile.io.req_1
  ID.io.regFileReq_B        <> regFile.io.req_2
  ID.io.regFileResp_A       <> regFile.io.resp_1
  ID.io.regFileResp_B       <> regFile.io.resp_2

  IDBarrier.io.pc_in        := IFBarrier.io.pc_out
  IDBarrier.io.inst_in      := IFBarrier.io.inst_out
  IDBarrier.io.inUOP        := ID.io.uop
  IDBarrier.io.inRD         := ID.io.rd
  IDBarrier.io.inRS1        := ID.io.rs1
  IDBarrier.io.inRS2        := ID.io.rs2
  IDBarrier.io.inOperandA   := ID.io.operandA
  IDBarrier.io.inOperandB   := ID.io.operandB

  /** TODO: Connect the I/Os of the forwarding unit */

    FU.io.inRS1     :=    IDBarrier.io.outRS1
    FU.io.inRS2     :=    IDBarrier.io.outRS2
    FU.io.inRD_mem  :=    EXBarrier.io.outRD
    FU.io.inRD_wb   :=    MEMBarrier.io.outRD
//    FU.io.RD_wb_2 :=    WBBarrier.io.outRD


  /* TODO: Implement MUXes to select which values are sent to the EX stage as operands*/

 /* val operandA = WireDefault(IDBarrier.io.outOperandA)
  val operandB = WireDefault(IDBarrier.io.outOperandB)

  switch(FU.io.forwardA) {
    is("b01".U) { operandA := EXBarrier.io.outAluResult }
    is("b10".U) { operandA := MEMBarrier.io.outAluResult }
  }

  switch(FU.io.forwardB) {
    is("b01".U) { operandB := EXBarrier.io.outAluResult }
    is("b10".U) { operandB := MEMBarrier.io.outAluResult }
  }*/

  //Mux(FU.io.forwardA === 0.U, operandA := IDBarrier.io.outOperandA, Mux(FU.io.forwardA === 1.U, operandA := EXBarrier.io.outAluResult, operandA := MEMBarrier.io.outAluResult))

//  EX.io.operandA := Mux(FU.io.forwardA === 0.U, IDBarrier.io.outOperandA, Mux(FU.io.forwardA === 1.U, EXBarrier.io.outAluResult, MEMBarrier.io.outAluResult))
//  EX.io.operandB := Mux(FU.io.forwardB === 0.U, IDBarrier.io.outOperandB, Mux(FU.io.forwardB === 1.U, EXBarrier.io.outAluResult, MEMBarrier.io.outAluResult))
  EX.io.operandA := Mux(FU.io.forwardA === 0.U, IDBarrier.io.outOperandA, Mux(FU.io.forwardA === 1.U, EXBarrier.io.outAluResult, Mux(FU.io.forwardA === 2.U,MEMBarrier.io.outAluResult,io.check_res)))
  EX.io.operandB := Mux(FU.io.forwardB === 0.U, IDBarrier.io.outOperandB, Mux(FU.io.forwardB === 1.U, EXBarrier.io.outAluResult, Mux(FU.io.forwardB === 2.U,MEMBarrier.io.outAluResult,io.check_res)))



//  assert(FU.io.forwardA =/= 3.U, "Message")

  EX.io.uop := IDBarrier.io.outUOP

  /* TODO: Connect operand inputs in EX stage to forwarding logic*/

 // EX.io.operandA := operandA
 // EX.io.operandB := operandB


  EXBarrier.io.pc_in        := IDBarrier.io.pc_out
  EXBarrier.io.inst_in      := IDBarrier.io.inst_out
  EXBarrier.io.inRD         := IDBarrier.io.outRD
  EXBarrier.io.inAluResult  := EX.io.aluResult

  MEMBarrier.io.pc_in        := EXBarrier.io.pc_out
  MEMBarrier.io.inst_in      := EXBarrier.io.inst_out
  MEMBarrier.io.inRD        := EXBarrier.io.outRD
  MEMBarrier.io.inAluResult := EXBarrier.io.outAluResult

  WB.io.rd                  := MEMBarrier.io.outRD
  WB.io.aluResult           := MEMBarrier.io.outAluResult
  WB.io.regFileReq          <> regFile.io.req_3

  WBBarrier.io.pc_in        := MEMBarrier.io.pc_out
  WBBarrier.io.inst_in        := MEMBarrier.io.inst_out
  WBBarrier.io.inCheckRes   := WB.io.check_res
  WBBarrier.io.inRD         := MEMBarrier.io.outRD

  pc_wb  := pc_mem
  pc_mem := pc_ex
  pc_ex  := pc_id
  pc_id  := pc_if
  pc_if  := IF.io.pc


  io.check_res              := WBBarrier.io.outCheckRes

}

