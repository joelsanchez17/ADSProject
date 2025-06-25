
{}
package core_tile

import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile

class BTBEntry extends Bundle {
  val valid = Bool()
  val tag = UInt(27.W)
  val target = UInt(32.W)
  val predictor = UInt(2.W)
}


class BranchTargetBuffer extends Module {
  val io = IO(new Bundle{
    val PC             = Input(UInt(32.W))
    val update         = Input(Bool())
    val updatePC       = Input(UInt(32.W))
    val updateTarget   = Input(UInt(32.W))
    val mispredicted   = Input(Bool())

    val valid          = Output(Bool())
    val target         = Output(UInt(32.W))
    val predictedTaken = Output(Bool())
  })


// BTBEntries [set][way]
  val btb0 = RegInit(Vec(8, new BTBEntry)) // Way 0
  val btb1 = RegInit(Vec(8, new BTBEntry)) // Way 1
  val lru = RegInit(VecInit(Seq.fill(8)(false.B)))


// Extract index and tag from PC
  val index = io.PC(4,2)           // 3 bits: 8 sets
  val tag = io.PC(31,5)            // 27-bit tag

//Identification if individual buffer base on index
  val entry0 = btb0(index)
  val entry1 = btb1(index)

// If tag from PC matchs with any tag from BTB (valid), then hit is true
  val hit0 = entry0.valid && entry0.tag === tag
  val hit1 = entry1.valid && entry1.tag === tag


  val hit = hit0 || hit1
  val hitEntry = Mux(hit0, entry0, entry1)

// Output predictions
  io.valid := hit
  io.target := Mux(hit, hitEntry.target, 0.U)
  io.predictedTaken := Mux(hit, hitEntry.predictor(1), false.B)

// Updating BTB

  when(io.update) {                      // After execution it is known is the branch was really taken
  val updIndex = io.updatePC(4, 2)      //  Address of the instruction
  val updTag = io.updatePC(31, 5)

  val w0 = btb0(updIndex)               // Reading from updatePC
  val w1 = btb1(updIndex)               // Reading from updatePC

  val match0 = w0.valid && w0.tag === updTag  //Check match
  val match1 = w1.valid && w1.tag === updTag


  when(match0) {
    // Update entry in way0
    w0.target := io.updateTarget
    // Logic of Predictor
    when(io.mispredicted) {
      w0.predictor := w0.predictor + 1.U
      } .otherwise {    // predictor is correct
      when(w0.predictor === (1.U || 3.0)) { w0.predictor := w0.predictor - 1.U }
      when(w0.predictor === (2.U || 0.U)) { w0.predictor := w0.predictor }
    }
    lru(updIndex) := true.B // mark way1 as LRU (way0 was just used)
  } .elsewhen(match1) {
    // Update entry in way1
    w1.target := io.updateTarget
    when(io.mispredicted) {
      w1.predictor := w1.predictor + 1.U
    } .otherwise {      // predictor is correct
    when(w1.predictor === (1.U || 3.0)) { w1.predictor := w1.predictor - 1.U }
    when(w1.predictor === (2.U || 0.U)) { w1.predictor := w1.predictor }
    }
    lru(updIndex) := false.B // mark way0 as LRU
  } .otherwise {
    // No match
    when(lru(updIndex)) {
      // Replace way0
      btb0(updIndex).valid := true.B
      btb0(updIndex).tag := updTag
      btb0(updIndex).target := io.updateTarget
      btb0(updIndex).predictor := "b10".U // weak taken
      lru(updIndex) := true.B
    } .otherwise {
      // Replace way1
      btb1(updIndex).valid := true.B
      btb1(updIndex).tag := updTag
      btb1(updIndex).target := io.updateTarget
      btb1(updIndex).predictor := "b10".U
      lru(updIndex) := false.B
      }
    }
  }
}
