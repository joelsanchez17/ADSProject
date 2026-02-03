
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
    test(new PipelinedRV32I("src/test/programs/BinaryFile"))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

        def parseBigInt(s: String): BigInt = {
          val t = s.trim.toLowerCase
          if (t.startsWith("0x")) BigInt(t.drop(2), 16) else BigInt(t)
        }

        def safePeek(signal: => Data): BigInt = {
          try signal.peek().litValue
          catch { case _: Throwable => BigInt(0) }
        }
        def b(x: => Data): BigInt = safePeek(x)

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

        def probeIO(moduleLabel: String, io: Record): Unit = {
          println(s"\n🔎 IO PROBE for $moduleLabel")
          io.elements.toSeq.sortBy(_._1).foreach { case (name, data) =>
            probe(s"$moduleLabel.io.$name", data)
          }
        }

        // TCP server
        val server = new ServerSocket(8888)
        println("\n🟦 [CHISEL] Waiting for Python on port 8888...")
        val client = server.accept()
        println("🟩 [CHISEL] Connected! Starting Loop...")

        val in  = new BufferedReader(new InputStreamReader(client.getInputStream))
        val out = new PrintWriter(client.getOutputStream, true)

        var cycle = 0L
        var running = true

        // until status (reported back to Python)
        var lastUntilHit = 0
        var lastUntilSteps = 0

        // sanity probes
        probe("dut.io.result", dut.io.result)
        probeIO("dut.io.dbg", dut.io.dbg)

        def breakpointHit(kind: String, value: BigInt): Boolean = {
          kind match {
            case "if_pc"    => b(dut.io.dbg.if_pc) == value
            case "id_pc"    => b(dut.io.dbg.id_pc) == value
            case "ex_pc"    => b(dut.io.dbg.ex_pc) == value
            case "mem_pc"   => b(dut.io.dbg.mem_pc) == value
            case "wb_pc"    => b(dut.io.dbg.wb_pc) == value
            case "wb_rd"    => b(dut.io.dbg.wb_rd) == value
            case "wb_we"    => b(dut.io.dbg.wb_we) == value
            case "mem_addr" => b(dut.io.dbg.mem_addr) == value
            case _          => false
          }
        }

        try {
          while (running) {

            val jsonState =
              s"""{
              "cycle": $cycle,
              "coreDone": ${b(dut.io.coreDone)},
              "gpRegVal": ${b(dut.io.gpRegVal)},
              "result": ${b(dut.io.result)},

              "until": { "hit": $lastUntilHit, "steps": $lastUntilSteps },

              "pc": {
                "if": ${b(dut.io.dbg.if_pc)},
                "id": ${b(dut.io.dbg.id_pc)},
                "ex": ${b(dut.io.dbg.ex_pc)},
                "mem": ${b(dut.io.dbg.mem_pc)},
                "wb": ${b(dut.io.dbg.wb_pc)}
              },

              "instr": {
                "if": ${b(dut.io.dbg.if_inst)},
                "id": ${b(dut.io.dbg.id_inst)},
                "ex": ${b(dut.io.dbg.ex_inst)},
                "mem": ${b(dut.io.dbg.mem_inst)},
                "wb": ${b(dut.io.dbg.wb_inst)}
              },

              "id": {
                "rs1": ${b(dut.io.dbg.id_rs1)},
                "rs2": ${b(dut.io.dbg.id_rs2)},
                "rd": ${b(dut.io.dbg.id_rd)},
                "we": ${b(dut.io.dbg.id_we)}
              },

              "hazard": {
                "pc_write": ${b(dut.io.dbg.pc_write)},
                "if_stall": ${b(dut.io.dbg.if_stall)},
                "id_stall": ${b(dut.io.dbg.id_stall)},
                "flush": ${b(dut.io.dbg.flush)}
              },

              "fwd": {
                "a_sel": ${b(dut.io.dbg.fwd_a_sel)},
                "b_sel": ${b(dut.io.dbg.fwd_b_sel)}
              },

              "ex": {
                "alu_result": ${b(dut.io.dbg.ex_alu_result)},
                "alu_op_a": ${peek(dut.io.dbg.ex_alu_op_a)},
                "alu_op_b": ${peek(dut.io.dbg.ex_alu_op_b)},
                "pc_src": ${b(dut.io.dbg.ex_pc_src)},
                "pc_jb": ${b(dut.io.dbg.ex_pc_jb)},
                "rd": ${b(dut.io.dbg.ex_rd)},
                "we": ${b(dut.io.dbg.ex_we)},
                "mem_rd_op": ${b(dut.io.dbg.ex_mem_rd_op)},
                "mem_wr_op": ${b(dut.io.dbg.ex_mem_wr_op)},
                "mem_to_reg": ${b(dut.io.dbg.ex_mem_to_reg)}
              },

              "mem": {
                "addr": ${b(dut.io.dbg.mem_addr)},
                "rd_op": ${b(dut.io.dbg.mem_rd_op)},
                "wr_op": ${b(dut.io.dbg.mem_wr_op)},
                "wdata": ${b(dut.io.dbg.mem_wdata)},
                "rdata": ${b(dut.io.dbg.mem_rdata)},
                "rd": ${b(dut.io.dbg.mem_rd)},
                "we": ${b(dut.io.dbg.mem_we)},
                "mem_to_reg": ${b(dut.io.dbg.mem_to_reg)}
              },

              "wb": {
                "rd": ${b(dut.io.dbg.wb_rd)},
                "we": ${b(dut.io.dbg.wb_we)},
                "wdata": ${b(dut.io.dbg.wb_wdata)},
                "check_res": ${b(dut.io.dbg.wb_check_res)}
              }
            }""".replaceAll("\n", " ").replaceAll("\\s+", " ").trim

            out.println(jsonState)

            val cmdLine = in.readLine()
            if (cmdLine == null) {
              running = false
              println("🟧 [CHISEL] Python disconnected.")
            } else {
              val cmd = cmdLine.trim
              if (cmd.nonEmpty) {
                val parts = cmd.split("\\s+").toList
                parts.head match {
                  case "quit" =>
                    running = false

                  case "step" =>
                    // reset until status
                    lastUntilHit = 0
                    lastUntilSteps = 0
                    dut.clock.step(1)
                    cycle += 1

                  case "run" =>
                    lastUntilHit = 0
                    lastUntilSteps = 0
                    val n = if (parts.length >= 2) parts(1).toInt else 1
                    if (n > 0) { dut.clock.step(n); cycle += n }

                  case "reset" =>
                    lastUntilHit = 0
                    lastUntilSteps = 0
                    dut.reset.poke(true.B)
                    dut.clock.step(1)
                    dut.reset.poke(false.B)
                    cycle = 0

                  // until <kind> <value> [max]
                  case "until" =>
                    if (parts.length >= 3) {
                      val kind  = parts(1)
                      val value = parseBigInt(parts(2))
                      val max   = if (parts.length >= 4) parts(3).toInt else 10000
                      var i = 0
                      var hit = false
                      while (i < max && !hit) {
                        dut.clock.step(1)
                        cycle += 1
                        hit = breakpointHit(kind, value)
                        i += 1
                      }
                      lastUntilHit = if (hit) 1 else 0
                      lastUntilSteps = i
                    }

                  case _ => // ignore
                }
              }
            }
          }
        } finally {
          out.close()
          in.close()
          client.close()
          server.close()
        }
      }
  }
}
