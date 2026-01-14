// ADS I Class Project
// Pipelined RISC-V Core with Hazard Detetcion and Resolution
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 05/21/2024 by Andro Mazmishvili (@Andrew8846)

package PipelinedRV32I

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile

import core_tile._

class PipelinedRV32I (BinaryFile: String) extends Module {

  val io     = IO(new Bundle {
    val result = Output(UInt(32.W)) 
    val coreDone = Output(UInt(1.W)) // unimp instruction has occured 
    val gpRegVal = Output(UInt(32.W)) // gp (x3) reg contains the riscv-tests pass fail status

    // Debug taps (top-level, always peekable)
    val dbg = new Bundle {
      val if_pc    = Output(UInt(32.W))
      val if_inst  = Output(UInt(32.W))
      val id_pc    = Output(UInt(32.W))
      val id_inst  = Output(UInt(32.W))
      val ex_pc    = Output(UInt(32.W))
      val ex_inst  = Output(UInt(32.W))
      val mem_pc   = Output(UInt(32.W))
      val mem_inst = Output(UInt(32.W))
      val wb_pc    = Output(UInt(32.W))
      val wb_inst  = Output(UInt(32.W))
    }
  })
  
  val core   = Module(new PipelinedRV32Icore)
  val IMem = Mem(4096, UInt(32.W))
  val DMem = Module(new DMEM(4096))
  loadMemoryFromFile(IMem, BinaryFile)

  io.result   := core.io.check_res
  io.coreDone := core.io.coreDone
  io.gpRegVal := core.io.gpRegVal
  core.io.imem.instr := IMem(core.io.imem.PC>>2.U)

  core.io.dmem <> DMem.io

  io.dbg.if_pc   := core.io.dbg.if_pc
  io.dbg.if_inst := core.io.dbg.if_inst

  io.dbg.id_pc   := core.io.dbg.id_pc
  io.dbg.id_inst := core.io.dbg.id_inst

  io.dbg.ex_pc   := core.io.dbg.ex_pc
  io.dbg.ex_inst := core.io.dbg.ex_inst

  io.dbg.mem_pc   := core.io.dbg.mem_pc
  io.dbg.mem_inst := core.io.dbg.mem_inst

  io.dbg.wb_pc   := core.io.dbg.wb_pc
  io.dbg.wb_inst := core.io.dbg.wb_inst


}

class DMEM (DEPTH: Int = 4096) extends Module {
    val io = IO(new DMEM_IO)

    val mem = Mem(DEPTH, UInt(32.W))

    when(io.wrEn === 1.U){
        mem(io.addr) := io.wData
    }
    io.rData := mem(io.addr)
}

