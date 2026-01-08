package PipelinedRV32I_Tester

import chisel3._
import chiseltest._
import PipelinedRV32I._
import org.scalatest.flatspec.AnyFlatSpec
import java.net._
import java.io._

class LivePipelineTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "PipelinedRV32I"

  it should "run in live mode waiting for Python" in {
      test(new PipelinedRV32I("src/test/programs/BinaryFile")).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

      val port = 8888
      val server = new ServerSocket(port)
      println(s"\n🟦 [CHISEL] Waiting for Python on port $port...")

      val client = server.accept()
      val out = new PrintStream(client.getOutputStream, true)
      val in = new BufferedReader(new InputStreamReader(client.getInputStream))
      println("🟩 [CHISEL] Connected! Starting Loop...")

      var cycle = 0
      var running = true

      // Helper to safely read signals or return 0 if they don't exist
      def peekOrZero(signal: => Data): BigInt = {
        // If the path is wrong, THIS WILL CRASH and tell us the real name!
        signal.peek().litValue
      }

      try {
        while (running) {

          // --- FIX: ADDED ".io." TO ALL PATHS ---
          val jsonState = s"""{
            "cycle": $cycle,
            "pc": ${peekOrZero(dut.io.result)},

            "STAGE_IF": ${peekOrZero(dut.core.IFBarrier.io.outPC)},
            "STAGE_ID": ${peekOrZero(dut.core.IDBarrier.io.outPC)},
            "STAGE_EX": ${peekOrZero(dut.core.EXBarrier.io.outPC)},
            "STAGE_MEM": ${peekOrZero(dut.core.MEMBarrier.io.outPC)},
            "STAGE_WB": ${peekOrZero(dut.core.WBBarrier.io.outPC)},

            "INSTR_IF": ${peekOrZero(dut.core.IFBarrier.io.outInstr)},
            "INSTR_ID": ${peekOrZero(dut.core.IDBarrier.io.outInstr)},
            "INSTR_EX": ${peekOrZero(dut.core.EXBarrier.io.outInstr)},
            "INSTR_MEM": ${peekOrZero(dut.core.MEMBarrier.io.outInstr)},
            "INSTR_WB": ${peekOrZero(dut.core.WBBarrier.io.outInstr)},

            "EX_ALU_RESULT": ${peekOrZero(dut.core.EX.io.aluResult)},
            "WB_DATA": ${peekOrZero(dut.core.WB.io.check_res)}
          }"""

          // Send to Python (remove newlines to keep it as one packet)
          out.println(jsonState.replaceAll("\n", " "))

          // Wait for command
          val cmd = in.readLine()

          if (cmd == null || cmd == "quit") {
            running = false
            println("🟧 [CHISEL] Python disconnected.")
          } else if (cmd == "step") {
            dut.clock.step(1)
            cycle += 1
          }
        }
      } catch {
        case e: Exception =>
          println(s"\n🛑 [CHISEL ERROR] $e")
          e.printStackTrace()
      } finally {
        out.close()
        in.close()
        client.close()
        server.close()
      }
    }
  }
}

