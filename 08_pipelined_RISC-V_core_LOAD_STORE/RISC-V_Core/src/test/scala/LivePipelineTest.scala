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
    test(new PipelinedRV32I("src/test/programs/BinaryFile")) { dut =>

      // CHANGE PORT to 8888 to avoid conflicts
      val port = 8888
      val server = new ServerSocket(port)
      println(s"\n🟦 [CHISEL] Waiting for Python on port $port...")

      val client = server.accept()
      // ENABLE AUTO-FLUSH (The 'true' argument)
      val out = new PrintStream(client.getOutputStream, true)
      val in = new BufferedReader(new InputStreamReader(client.getInputStream))
      println("🟩 [CHISEL] Connected! Starting Loop...")

      var cycle = 0
      var running = true

      try {
        while (running) {
          // Access PC or Result
          val currentPC = dut.io.result.peek().litValue

          // Send JSON
          out.println(s"""{"cycle": $cycle, "pc": $currentPC}""")
          // out.flush() is not needed because we enabled auto-flush above

          // Wait for Command
          // We print what we are doing to debug
          println(s"   [CHISEL] Sent State $cycle. Waiting for command...")
          val cmd = in.readLine()

          if (cmd == null) {
            running = false
            println("🟧 [CHISEL] Received null (EOF). Python closed the connection.")
          } else if (cmd == "quit") {
            running = false
            println("🟧 [CHISEL] Python sent 'quit'.")
          } else if (cmd == "step") {
            println("   [CHISEL] Stepping...")
            dut.clock.step(1)
            cycle += 1
          }
        }
      } catch {
        case e: Exception =>
          println(s"\n🛑 [CHISEL ERROR] $e")
          e.printStackTrace()
      } finally {
        // CLOSE EVERYTHING
        out.close()
        in.close()
        client.close()
        server.close()
      }
    }
  }
}

