
import BranchTargetBuffer.BranchTargetBuffer
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class BTBTester extends AnyFlatSpec with ChiselScalatestTester {

 "Simple BTB Test" should "insert and predict one branch correctly" in {
    test(new BranchTargetBuffer).withAnnotations(Seq(WriteVcdAnnotation)){ c =>

        // 2 branches into the same set
        val pc0 = 0x04.U   // index = 1, tag = 0
        val pc1 = 0x24.U   // index = 1, different tag = 1
        // index 1000 = 1
        // tag 00000000 00000000 00000000 0001 = 1

        val target0 = 0x100.U
        val target1 = 0x200.U

        // Poke with pc0 branch
        c.io.update.poke(true.B)
        c.io.updatePC.poke(pc0)
        c.io.updateTarget.poke(target0)
        c.io.mispredicted.poke(true.B) // will initialize predictor
        c.clock.step()

        // Poke with pc1 branch (same set (index:1), evict based on LRU)
        c.io.updatePC.poke(pc1)
        c.io.updateTarget.poke(target1)
        c.io.mispredicted.poke(true.B)
        c.clock.step()


        // Predict using pc0 and pc1

        c.io.update.poke(false.B)
        c.io.PC.poke(pc0)
        c.clock.step()
        c.io.valid.expect(true.B)
        c.io.target.expect(target0)
        c.io.predictedTaken.expect(true.B)

        c.io.PC.poke(pc1)
        c.clock.step()
        c.io.valid.expect(true.B)
        c.io.target.expect(target1)
        c.io.predictedTaken.expect(true.B)



        // Forcing eviction: insert pc2 into same set
        val pc2 = 0x44.U  // same index = 1, tag = 2
        val target2 = 0x300.U

        c.io.update.poke(true.B)
        c.io.updatePC.poke(pc2)
        c.io.updateTarget.poke(target2)
        c.io.mispredicted.poke(true.B)
        c.clock.step(2)

        // Only 2 of the 3 branches should be in BTB (due to LRU)
        c.io.update.poke(false.B)

        // Test prediction for pc0 again (should be evicted or still present depending on LRU)
        c.io.PC.poke(pc0)
        c.clock.step()
        c.io.valid.expect(false.B)
        c.io.target.expect(0.U)
        c.io.predictedTaken.expect(false.B)
        

        // Test FSM transitions

        // Repeat pc2 update with mispredicted = false to move toward not taken
        for (_ <- 0 until 3) {
            c.io.update.poke(true.B)
            c.io.updatePC.poke(pc2)
            c.io.updateTarget.poke(target2)
            c.io.mispredicted.poke(false.B) // prediction was correct
            c.clock.step()
        }
        // Now prediction should still be "taken". Predictor is at 0 (strong taken)
        c.io.update.poke(false.B)  // stop updating
        c.io.PC.poke(pc2)          // input pc2
        c.clock.step()

        // ✅ Expected output of BTB based on predictor = 0
        c.io.valid.expect(true.B)             // pc2 is still stored in BTB
        c.io.predictedTaken.expect(false.B)    // predict taken
        c.io.target.expect(target2)           // correct target address returned

    }
 }
}
//
//        // Now prediction should change from weak taken to not taken
//        c.io.update.poke(false.B)
//        c.io.PC.poke(pc2)
//        c.clock.step()
//        println(s"[FSM test] PC2 -> Valid: ${c.io.valid.peek()}, Taken: ${c.io.predictedTaken.peek()}, Target: ${c.io.target.peek()}")
//    }
// }




