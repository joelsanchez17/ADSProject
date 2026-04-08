package core_tile

import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile

import ALUOpT._
import aluOpAMux._
import aluOpBMux._

// -----------------------------------------
// IF-Barrier
// -----------------------------------------
class IFBarrier extends Module {
    val io = IO(new Bundle {
        val if_stall = Input(UInt(1.W))
        val flush    = Input(UInt(1.W))
        val inInstr  = Input(UInt(32.W))
        val inPC     = Input(UInt(32.W))
        val outInstr = Output(UInt(32.W))
        val outPC    = Output(UInt(32.W))
    })

    // Explicit, named regs (stable + peekable)
    val pcReg    = RegInit(0.U(32.W))
    val instrReg = RegInit(0x00000013.U(32.W)) // NOP

    when(io.if_stall === 0.U) {
        when(io.flush === 0.U) {
            instrReg := io.inInstr
            pcReg    := io.inPC
        }.otherwise {
            instrReg := 0x00000013.U
            pcReg    := 0.U
        }
    }

    io.outInstr := instrReg
    io.outPC    := pcReg

    // Force keep (Chisel 3.5: dontTouch is available from chisel3._)
    dontTouch(pcReg)
    dontTouch(instrReg)

    // Optional: also mark the ports (useful if you string-peek ports)
    dontTouch(io.outPC)
    dontTouch(io.outInstr)
}

// -----------------------------------------
// ID-Barrier
// -----------------------------------------
class IDBarrier extends Module {
    val io = IO(new Bundle {
        val id_stall   = Input(UInt(1.W))
        val flush      = Input(UInt(1.W))
        val inALUOp    = Input(ALUOpT())
        val inInstr    = Input(UInt(32.W))
        val inRS1      = Input(UInt(5.W))
        val inRS2      = Input(UInt(5.W))
        val inOperandA = Input(UInt(32.W))
        val inOperandB = Input(UInt(32.W))
        val inImme     = Input(UInt(32.W))
        val inPC       = Input(UInt(32.W))
        val inAluSrcB  = Input(aluOpBImmMux())
        val inAluSrcA  = Input(aluOpAPCMux())
        val inMemRd    = Input(memRdOpT())
        val inMemWr    = Input(memWrOpT())
        val inMemtoReg = Input(UInt(1.W))
        val inRD       = Input(UInt(5.W))
        val inWrEn     = Input(UInt(1.W))

        val outALUOp    = Output(ALUOpT())
        val outInstr    = Output(UInt(32.W))
        val outRS1      = Output(UInt(5.W))
        val outRS2      = Output(UInt(5.W))
        val outOperandA = Output(UInt(32.W))
        val outOperandB = Output(UInt(32.W))
        val outImme     = Output(UInt(32.W))
        val outPC       = Output(UInt(32.W))
        val outAluSrcA  = Output(aluOpAPCMux())
        val outAluSrcB  = Output(aluOpBImmMux())
        val outMemRd    = Output(memRdOpT())
        val outMemWr    = Output(memWrOpT())
        val outMemtoReg = Output(UInt(1.W))
        val outRD       = Output(UInt(5.W))
        val outWrEn     = Output(UInt(1.W))
    })

    val injectBubble = (io.flush | io.id_stall) === 1.U

    // Debug-critical regs: explicit RegInit + explicit update
    val pcReg    = RegInit(0.U(32.W))
    val instrReg = RegInit(0x00000013.U(32.W))

    when(injectBubble) {
        pcReg    := 0.U
        instrReg := 0x00000013.U
    }.otherwise {
        pcReg    := io.inPC
        instrReg := io.inInstr
    }

    io.outPC    := pcReg
    io.outInstr := instrReg

    dontTouch(pcReg)
    dontTouch(instrReg)
    dontTouch(io.outPC)
    dontTouch(io.outInstr)

    // Other pipeline regs (kept as before, but explicit RegInit keeps names nicer)
    val aluOpReg    = RegInit(ALUOpT.invalid)
    val rdReg       = RegInit(0.U(5.W))
    val rs1Reg      = RegInit(0.U(5.W))
    val rs2Reg      = RegInit(0.U(5.W))
    val opAReg      = RegInit(0.U(32.W))
    val opBReg      = RegInit(0.U(32.W))
    val immeReg     = RegInit(0.U(32.W))
    val aluSrcAReg  = RegInit(aluOpAPCMux.forwardMuxA)
    val aluSrcBReg  = RegInit(aluOpBImmMux.forwardMuxB)
    val wrEnReg     = RegInit(0.U(1.W))
    val memRdReg    = RegInit(memRdOpT.IDLE)
    val memWrReg    = RegInit(memWrOpT.IDLE)
    val memToRegReg = RegInit(0.U(1.W))

    when(injectBubble) {
        aluOpReg    := ALUOpT.invalid
        rdReg       := 0.U
        rs1Reg      := 0.U
        rs2Reg      := 0.U
        opAReg      := 0.U
        opBReg      := 0.U
        immeReg     := 0.U
        aluSrcAReg  := aluOpAPCMux.forwardMuxA
        aluSrcBReg  := aluOpBImmMux.forwardMuxB
        wrEnReg     := 0.U
        memRdReg    := memRdOpT.IDLE
        memWrReg    := memWrOpT.IDLE
        memToRegReg := 0.U
    }.otherwise {
        aluOpReg    := io.inALUOp
        rdReg       := io.inRD
        rs1Reg      := io.inRS1
        rs2Reg      := io.inRS2
        opAReg      := io.inOperandA
        opBReg      := io.inOperandB
        immeReg     := io.inImme
        aluSrcAReg  := io.inAluSrcA
        aluSrcBReg  := io.inAluSrcB
        wrEnReg     := io.inWrEn
        memRdReg    := io.inMemRd
        memWrReg    := io.inMemWr
        memToRegReg := io.inMemtoReg
    }

    io.outALUOp    := aluOpReg
    io.outRD       := rdReg
    io.outRS1      := rs1Reg
    io.outRS2      := rs2Reg
    io.outOperandA := opAReg
    io.outOperandB := opBReg
    io.outImme     := immeReg
    io.outAluSrcA  := aluSrcAReg
    io.outAluSrcB  := aluSrcBReg
    io.outWrEn     := wrEnReg
    io.outMemRd    := memRdReg
    io.outMemWr    := memWrReg
    io.outMemtoReg := memToRegReg
}

// -----------------------------------------
// EX/MEM/WB Barriers
// Same idea: explicit pcReg/instrReg as RegInit + dontTouch,
// and you can keep the rest as RegInit or RegNext.
// -----------------------------------------
class EXBarrier extends Module {
    val io = IO(new Bundle {
        val inAluResult = Input(UInt(32.W))
        val inRD        = Input(UInt(5.W))
        val inMemWrData = Input(UInt(32.W))
        val inMemRd     = Input(memRdOpT())
        val inMemWr     = Input(memWrOpT())
        val inMemtoReg  = Input(UInt(1.W))
        val inWrEn      = Input(UInt(1.W))
        val inPCSrc     = Input(UInt(1.W))
        val inPC_JB     = Input(UInt(32.W))
        val inPC        = Input(UInt(32.W))
        val inInstr     = Input(UInt(32.W))

        val outPC        = Output(UInt(32.W))
        val outInstr     = Output(UInt(32.W))
        val outAluResult = Output(UInt(32.W))
        val outRD        = Output(UInt(5.W))
        val outMemWrData = Output(UInt(32.W))
        val outMemRd     = Output(memRdOpT())
        val outMemWr     = Output(memWrOpT())
        val outMemtoReg  = Output(UInt(1.W))
        val outWrEn      = Output(UInt(1.W))
        val outPCSrc     = Output(UInt(1.W))
        val outPC_JB     = Output(UInt(32.W))
    })

    val pcReg    = RegInit(0.U(32.W))
    val instrReg = RegInit(0x00000013.U(32.W))

    pcReg    := io.inPC
    instrReg := io.inInstr

    io.outPC    := pcReg
    io.outInstr := instrReg

    dontTouch(pcReg);    dontTouch(io.outPC)
    dontTouch(instrReg); dontTouch(io.outInstr)

    io.outAluResult := RegNext(io.inAluResult, 0.U)
    io.outRD        := RegNext(io.inRD, 0.U)
    io.outMemWrData := RegNext(io.inMemWrData, 0.U)
    io.outMemRd     := RegNext(io.inMemRd, memRdOpT.IDLE)
    io.outMemWr     := RegNext(io.inMemWr, memWrOpT.IDLE)
    io.outMemtoReg  := RegNext(io.inMemtoReg, 0.U)
    io.outWrEn      := RegNext(io.inWrEn, 0.U)
    io.outPCSrc     := RegNext(io.inPCSrc, 0.U)
    io.outPC_JB     := RegNext(io.inPC_JB, 0.U)
}

class MEMBarrier extends Module {
    val io = IO(new Bundle {
        val inAluResult = Input(UInt(32.W))
        val inMemData   = Input(UInt(32.W))
        val inMemtoReg  = Input(UInt(1.W))
        val inRD        = Input(UInt(5.W))
        val inWrEn      = Input(UInt(1.W))
        val inPC        = Input(UInt(32.W))
        val inInstr     = Input(UInt(32.W))

        val outPC        = Output(UInt(32.W))
        val outInstr     = Output(UInt(32.W))
        val outAluResult = Output(UInt(32.W))
        val outMemData   = Output(UInt(32.W))
        val outMemtoReg  = Output(UInt(1.W))
        val outRD        = Output(UInt(5.W))
        val outWrEn      = Output(UInt(1.W))
    })

    val pcReg    = RegInit(0.U(32.W))
    val instrReg = RegInit(0x00000013.U(32.W))

    pcReg    := io.inPC
    instrReg := io.inInstr

    io.outPC    := pcReg
    io.outInstr := instrReg

    dontTouch(pcReg);    dontTouch(io.outPC)
    dontTouch(instrReg); dontTouch(io.outInstr)

    io.outAluResult := RegNext(io.inAluResult, 0.U)
    io.outMemData   := RegNext(io.inMemData, 0.U)
    io.outMemtoReg  := RegNext(io.inMemtoReg, 0.U)
    io.outRD        := RegNext(io.inRD, 0.U)
    io.outWrEn      := RegNext(io.inWrEn, 0.U)
}

class WBBarrier extends Module {
    val io = IO(new Bundle {
        val inCheckRes = Input(UInt(32.W))
        val inPC       = Input(UInt(32.W))
        val inInstr    = Input(UInt(32.W))

        val outPC       = Output(UInt(32.W))
        val outInstr    = Output(UInt(32.W))
        val outCheckRes = Output(UInt(32.W))
    })

    val checkResReg = RegInit(0.U(32.W))
    checkResReg := io.inCheckRes
    io.outCheckRes := checkResReg

    val pcReg    = RegInit(0.U(32.W))
    val instrReg = RegInit(0x00000013.U(32.W))

    pcReg    := io.inPC
    instrReg := io.inInstr

    io.outPC    := pcReg
    io.outInstr := instrReg

    dontTouch(pcReg);    dontTouch(io.outPC)
    dontTouch(instrReg); dontTouch(io.outInstr)
}


