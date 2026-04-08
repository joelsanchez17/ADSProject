package core_tile

import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile

import ALUOpT._
import aluOpAMux._
import aluOpBMux._

// -----------------------------------------
// Main Class
// -----------------------------------------

class PipelinedRV32Icore extends Module {
    val io = IO(new Bundle {
        val check_res = Output(UInt(32.W))
        val coreDone = Output(UInt(1.W))
        val gpRegVal = Output(UInt(32.W)) // gp (x3) reg is contains the riscv-tests pass fail status
        val dmem = Flipped(new DMEM_IO)
        val imem = Flipped(new IMEM_IO)

        // -----------------------------
        // Debug bus: educational signals
        // -----------------------------
        val dbg = new Bundle {
            // Pipeline PCs & instructions
            val if_pc    = Output(UInt(32.W)); val if_inst  = Output(UInt(32.W))
            val id_pc    = Output(UInt(32.W)); val id_inst  = Output(UInt(32.W))
            val ex_pc    = Output(UInt(32.W)); val ex_inst  = Output(UInt(32.W))
            val mem_pc   = Output(UInt(32.W)); val mem_inst = Output(UInt(32.W))
            val wb_pc    = Output(UInt(32.W)); val wb_inst  = Output(UInt(32.W))

            // Register ids (handy for hazard/forwarding teaching)
            val id_rs1   = Output(UInt(5.W))
            val id_rs2   = Output(UInt(5.W))
            val id_rd    = Output(UInt(5.W))
            val id_we    = Output(UInt(1.W))

            // Hazard / control
            val pc_write = Output(UInt(1.W))
            val if_stall = Output(UInt(1.W))
            val id_stall = Output(UInt(1.W))
            val flush    = Output(UInt(1.W))

            // Forwarding selectors (exported as UInt because enums are hard to peek)
            val fwd_a_sel = Output(UInt(8.W))
            val fwd_b_sel = Output(UInt(8.W))

            // EX stage summary
            val ex_alu_result = Output(UInt(32.W))
            val ex_alu_op_a   = Output(UInt(32.W))
            val ex_alu_op_b   = Output(UInt(32.W))
            val ex_pc_src     = Output(UInt(1.W))
            val ex_pc_jb      = Output(UInt(32.W))
            val ex_rd         = Output(UInt(5.W))
            val ex_we         = Output(UInt(1.W))
            val ex_mem_rd_op  = Output(UInt(8.W))
            val ex_mem_wr_op  = Output(UInt(8.W))
            val ex_mem_to_reg = Output(UInt(1.W))

            // MEM stage summary
            val mem_addr    = Output(UInt(32.W))
            val mem_rd_op   = Output(UInt(8.W))
            val mem_wr_op   = Output(UInt(8.W))
            val mem_wdata   = Output(UInt(32.W))
            val mem_rdata   = Output(UInt(32.W))
            val mem_rd      = Output(UInt(5.W))
            val mem_we      = Output(UInt(1.W))
            val mem_to_reg  = Output(UInt(1.W))

            // WB stage summary
            val wb_rd        = Output(UInt(5.W))
            val wb_we        = Output(UInt(1.W))
            val wb_wdata     = Output(UInt(32.W))
            val wb_check_res = Output(UInt(32.W))
            val regs         = Output(Vec(32, UInt(32.W)))
        }
    })

    // Pipeline Registers
    val IFBarrier  = Module(new IFBarrier)
    val IDBarrier  = Module(new IDBarrier)
    val EXBarrier  = Module(new EXBarrier)
    val MEMBarrier = Module(new MEMBarrier)
    val WBBarrier  = Module(new WBBarrier)

    // Pipeline Stages
    val IF  = Module(new IF)
    val ID  = Module(new ID)
    val EX  = Module(new EX)
    val MEM = Module(new MEM)
    val WB  = Module(new WB)

    val ForwardingUnit_inst = Module(new ForwardingUnit)
    val RegFile_inst = Module(new RegFile)
    val HazardDetectionUnit_inst = Module(new HazardDetectionUnit)
    val ControlUnit_inst = Module(new ControlUnit)

    IF.io.PCWrite   := HazardDetectionUnit_inst.io.pcWrite
    IF.io.PCSrc     := EXBarrier.io.outPCSrc
    IF.io.PC_JB     := EXBarrier.io.outPC_JB
    IF.io.instrMem  := io.imem.instr
    io.imem.PC   := IF.io.PCMem

    IFBarrier.io.inInstr  := IF.io.instr
    IFBarrier.io.inPC     := IF.io.pc
    IFBarrier.io.if_stall := HazardDetectionUnit_inst.io.if_stall
    IFBarrier.io.flush    := EX.io.flush

    HazardDetectionUnit_inst.io.instr       := IFBarrier.io.outInstr
    HazardDetectionUnit_inst.io.ex_RD       := IDBarrier.io.outRD
    HazardDetectionUnit_inst.io.ex_memRd    := IDBarrier.io.outMemRd

    ControlUnit_inst.io.instr := IFBarrier.io.outInstr

    ID.io.instr               := IFBarrier.io.outInstr
    IDBarrier.io.inInstr      := IFBarrier.io.outInstr
    IDBarrier.io.inPC         := IFBarrier.io.outPC
    IDBarrier.io.flush        := EX.io.flush
    IDBarrier.io.id_stall     := HazardDetectionUnit_inst.io.id_stall
    IDBarrier.io.inRS1        := ID.io.rs1
    IDBarrier.io.inRS2        := ID.io.rs2
    IDBarrier.io.inOperandA   := ID.io.operandA
    IDBarrier.io.inOperandB   := ID.io.operandB
    IDBarrier.io.inImme       := ID.io.imme
    IDBarrier.io.inRD         := ID.io.rd
    IDBarrier.io.inWrEn       := ControlUnit_inst.io.wrEn
    IDBarrier.io.inMemtoReg   := ControlUnit_inst.io.memtoReg
    IDBarrier.io.inMemRd      := ControlUnit_inst.io.memRd
    IDBarrier.io.inMemWr      := ControlUnit_inst.io.memWr
    IDBarrier.io.inALUOp      := ControlUnit_inst.io.ALUOp
    IDBarrier.io.inAluSrcA    := ControlUnit_inst.io.ALUSrcA
    IDBarrier.io.inAluSrcB    := ControlUnit_inst.io.ALUSrcB

    ID.io.regFileReq_A        <> RegFile_inst.io.req_1
    ID.io.regFileReq_B        <> RegFile_inst.io.req_2
    ID.io.regFileResp_A       <> RegFile_inst.io.resp_1
    ID.io.regFileResp_B       <> RegFile_inst.io.resp_2

    ForwardingUnit_inst.io.rs1_id   := IDBarrier.io.outRS1
    ForwardingUnit_inst.io.rs2_id   := IDBarrier.io.outRS2
    ForwardingUnit_inst.io.instr   := IDBarrier.io.outInstr
    ForwardingUnit_inst.io.rd_mem   := EXBarrier.io.outRD
    ForwardingUnit_inst.io.wrEn_mem := EXBarrier.io.outWrEn
    ForwardingUnit_inst.io.rd_wb    := MEMBarrier.io.outRD
    ForwardingUnit_inst.io.wrEn_wb  := MEMBarrier.io.outWrEn

    EX.io.ALUOp     := IDBarrier.io.outALUOp
    EX.io.ALUSrcA   := IDBarrier.io.outAluSrcA
    EX.io.ALUSrcB   := IDBarrier.io.outAluSrcB
    EX.io.imme      := IDBarrier.io.outImme
    EX.io.PC        := IDBarrier.io.outPC
    EX.io.instr     := IDBarrier.io.outInstr

    EX.io.operandA  := IDBarrier.io.outOperandA // default case
    EX.io.operandB  := IDBarrier.io.outOperandB // default case
    switch(ForwardingUnit_inst.io.aluOpA_ctrl){
        is(aluOpAMux.opA_id)        {EX.io.operandA := IDBarrier.io.outOperandA}
        is(aluOpAMux.AluResult_mem) {EX.io.operandA := EXBarrier.io.outAluResult}
        is(aluOpAMux.AluResult_wb)  {EX.io.operandA := WB.io.regFileReq.data}
    }
    switch(ForwardingUnit_inst.io.aluOpB_ctrl){
        is(aluOpBMux.opB_id)        {EX.io.operandB := IDBarrier.io.outOperandB}
        is(aluOpBMux.AluResult_mem) {EX.io.operandB := EXBarrier.io.outAluResult}
        is(aluOpBMux.AluResult_wb)  {EX.io.operandB := WB.io.regFileReq.data}
    }

    EXBarrier.io.inInstr      := IDBarrier.io.outInstr
    EXBarrier.io.inPC         := IDBarrier.io.outPC

    EXBarrier.io.inAluResult  := EX.io.aluResult
    EXBarrier.io.inRD         := IDBarrier.io.outRD
    EXBarrier.io.inMemWrData  := EX.io.operandB // output of forward mux
    EXBarrier.io.inMemRd      := IDBarrier.io.outMemRd
    EXBarrier.io.inMemWr      := IDBarrier.io.outMemWr
    EXBarrier.io.inMemtoReg   := IDBarrier.io.outMemtoReg
    EXBarrier.io.inWrEn       := IDBarrier.io.outWrEn
    EXBarrier.io.inPCSrc      := EX.io.PCSrc
    EXBarrier.io.inPC_JB      := EX.io.PC_JB

    MEM.io.addr         := EXBarrier.io.outAluResult
    MEM.io.memRd        := EXBarrier.io.outMemRd
    MEM.io.memWr        := EXBarrier.io.outMemWr
    MEM.io.writeData    := EXBarrier.io.outMemWrData
    MEM.io.dmem         <> io.dmem

    MEMBarrier.io.inPC         := EXBarrier.io.outPC
    MEMBarrier.io.inInstr      := EXBarrier.io.outInstr
    MEMBarrier.io.inAluResult  := EXBarrier.io.outAluResult
    MEMBarrier.io.inMemData    := MEM.io.readData
    MEMBarrier.io.inMemtoReg   := EXBarrier.io.outMemtoReg
    MEMBarrier.io.inRD         := EXBarrier.io.outRD
    MEMBarrier.io.inWrEn       := EXBarrier.io.outWrEn

    WB.io.rd            := MEMBarrier.io.outRD
    WB.io.aluResult     := MEMBarrier.io.outAluResult
    WB.io.memData       := MEMBarrier.io.outMemData
    WB.io.memtoReg      := MEMBarrier.io.outMemtoReg
    WB.io.wrEn          := MEMBarrier.io.outWrEn
    WB.io.regFileReq    <> RegFile_inst.io.req_3

    WBBarrier.io.inPC         := MEMBarrier.io.outPC
    WBBarrier.io.inInstr      := MEMBarrier.io.outInstr
    WBBarrier.io.inCheckRes   := WB.io.check_res

    // -----------------------------
    // Debug bus assignments
    // -----------------------------
    io.dbg.if_pc   := IFBarrier.io.inPC
    io.dbg.if_inst := IFBarrier.io.inInstr

    io.dbg.id_pc   := IDBarrier.io.inPC
    io.dbg.id_inst := IDBarrier.io.inInstr

    io.dbg.ex_pc   := EXBarrier.io.inPC
    io.dbg.ex_inst := EXBarrier.io.inInstr

    io.dbg.mem_pc   := MEMBarrier.io.inPC
    io.dbg.mem_inst := MEMBarrier.io.inInstr

    io.dbg.wb_pc   := WBBarrier.io.inPC
    io.dbg.wb_inst := WBBarrier.io.inInstr

    // Register identifiers (from ID barrier)
    io.dbg.id_rs1 := IDBarrier.io.inRS1
    io.dbg.id_rs2 := IDBarrier.io.inRS2
    io.dbg.id_rd  := IDBarrier.io.inRD
    io.dbg.id_we  := IDBarrier.io.outWrEn

    io.dbg.pc_write := HazardDetectionUnit_inst.io.pcWrite
    io.dbg.if_stall := HazardDetectionUnit_inst.io.if_stall
    io.dbg.id_stall := HazardDetectionUnit_inst.io.id_stall
    io.dbg.flush    := EX.io.flush

    io.dbg.fwd_a_sel := ForwardingUnit_inst.io.aluOpA_ctrl.asUInt
    io.dbg.fwd_b_sel := ForwardingUnit_inst.io.aluOpB_ctrl.asUInt


    io.dbg.ex_alu_result := EX.io.aluResult
    io.dbg.ex_alu_op_a   := EX.io.aluInputA
    io.dbg.ex_alu_op_b   := EX.io.aluInputB
    io.dbg.ex_pc_src     := EXBarrier.io.outPCSrc
    io.dbg.ex_pc_jb      := EXBarrier.io.outPC_JB
    io.dbg.ex_rd         := EXBarrier.io.outRD
    io.dbg.ex_we         := EXBarrier.io.outWrEn
    io.dbg.ex_mem_rd_op  := EXBarrier.io.outMemRd.asUInt
    io.dbg.ex_mem_wr_op  := EXBarrier.io.outMemWr.asUInt
    io.dbg.ex_mem_to_reg := EXBarrier.io.outMemtoReg

    io.dbg.mem_addr   := MEM.io.addr
    io.dbg.mem_rd_op  := MEM.io.memRd.asUInt
    io.dbg.mem_wr_op  := MEM.io.memWr.asUInt
    io.dbg.mem_wdata  := MEM.io.writeData
    io.dbg.mem_rdata  := MEM.io.readData
    io.dbg.mem_rd     := MEMBarrier.io.outRD
    io.dbg.mem_we     := MEMBarrier.io.outWrEn
    io.dbg.mem_to_reg := MEMBarrier.io.outMemtoReg

    io.dbg.wb_rd        := WB.io.rd
    io.dbg.wb_we        := WB.io.wrEn
    io.dbg.wb_wdata     := WB.io.regFileReq.data
    io.dbg.wb_check_res := WB.io.check_res

    io.check_res := WBBarrier.io.outCheckRes
    io.coreDone  := HazardDetectionUnit_inst.io.coreDone
    io.gpRegVal  := RegFile_inst.io.gpRegVal
    io.dbg.regs := RegFile_inst.io.debug_regs
}


