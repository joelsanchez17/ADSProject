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
    // Keep the Annotation to ensure 'dontTouch' works optimally
    test(new PipelinedRV32I("src/test/programs/BinaryFile")).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

      val port = 8888
      val server = new ServerSocket(port)
      println(s"\n🟦 [CHISEL] Waiting for Python on port $port...")

      val client = server.accept()
      val out = new PrintStream(client.getOutputStream, true)
      val in = new BufferedReader(new InputStreamReader(client.getInputStream))
      println("🟩 [CHISEL] Connected! Starting Loop...")


      // Dumps a Data's simulator target and whether it is peekable
      def probe(label: String, d: Data): Unit = {
        val target = d.toTarget.serialize
        try {
          val v = d.peek().litValue
          println(f"✅ PROBE $label%-30s  target=$target  value=0x${v.toString(16)}")
        } catch {
          case e: Throwable =>
            println(s"❌ PROBE $label  target=$target  ERROR=${e.getClass.getSimpleName}: ${e.getMessage}")
        }
      }

      // Lists all IO fields of a module and probes them
      def probeIO(moduleLabel: String, io: Record): Unit = {
        println(s"\n🔎 IO PROBE for $moduleLabel")
        io.elements.foreach { case (name, data) =>
          probe(s"$moduleLabel.io.$name", data)
        }
      }







      // --- One-time debug dump: what signals really exist + are peekable? ---
      probe("dut.io.result", dut.io.result)

      probeIO("dut.io.dbg", dut.io.dbg)




      var cycle = 0
      var running = true

      // "Unsafe" Peek - We WANT it to crash if the key is missing so we know!
      def peekOrZero(signal: => Data): BigInt = {
        signal.peek().litValue
      }




      try {
        while (running) {

          val jsonState = s"""{
          "cycle": $cycle,
          "result": ${peekOrZero(dut.io.result)},

          "STAGE_IF": ${peekOrZero(dut.io.dbg.if_pc)},
          "STAGE_ID": ${peekOrZero(dut.io.dbg.id_pc)},
          "STAGE_EX": ${peekOrZero(dut.io.dbg.ex_pc)},
          "STAGE_MEM": ${peekOrZero(dut.io.dbg.mem_pc)},
          "STAGE_WB": ${peekOrZero(dut.io.dbg.wb_pc)},

          "INSTR_IF": ${peekOrZero(dut.io.dbg.if_inst)},
          "INSTR_ID": ${peekOrZero(dut.io.dbg.id_inst)},
          "INSTR_EX": ${peekOrZero(dut.io.dbg.ex_inst)},
          "INSTR_MEM": ${peekOrZero(dut.io.dbg.mem_inst)},
          "INSTR_WB": ${peekOrZero(dut.io.dbg.wb_inst)}


        }"""

          out.println(jsonState.replaceAll("\n", " "))

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

