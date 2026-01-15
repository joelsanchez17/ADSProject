#!/usr/bin/env python3
import socket
import json
import argparse
from dataclasses import dataclass
from typing import Optional, Dict, Any, List

REG = [f"x{i}" for i in range(32)]

def sign_extend(value: int, bits: int) -> int:
    sign = 1 << (bits - 1)
    return (value & (sign - 1)) - (value & sign)

def decode_rv32i(instr: int) -> str:
    instr &= 0xFFFFFFFF
    opcode = instr & 0x7F
    rd     = (instr >> 7) & 0x1F
    funct3 = (instr >> 12) & 0x7
    rs1    = (instr >> 15) & 0x1F
    rs2    = (instr >> 20) & 0x1F
    funct7 = (instr >> 25) & 0x7F

    def i_imm(): return sign_extend((instr >> 20) & 0xFFF, 12)
    def s_imm():
        imm = ((instr >> 7) & 0x1F) | (((instr >> 25) & 0x7F) << 5)
        return sign_extend(imm, 12)
    def b_imm():
        imm = (((instr >> 8) & 0xF) << 1) \
              | (((instr >> 25) & 0x3F) << 5) \
              | (((instr >> 7) & 0x1) << 11) \
              | (((instr >> 31) & 0x1) << 12)
        return sign_extend(imm, 13)
    def u_imm(): return instr & 0xFFFFF000
    def j_imm():
        imm = (((instr >> 21) & 0x3FF) << 1) \
              | (((instr >> 20) & 0x1) << 11) \
              | (((instr >> 12) & 0xFF) << 12) \
              | (((instr >> 31) & 0x1) << 20)
        return sign_extend(imm, 21)

    if instr == 0x00000013:
        return "nop"

    if opcode == 0x37: return f"lui {REG[rd]}, {u_imm():#x}"
    if opcode == 0x17: return f"auipc {REG[rd]}, {u_imm():#x}"
    if opcode == 0x6F: return f"jal {REG[rd]}, {j_imm():+d}"
    if opcode == 0x67 and funct3 == 0x0:
        return f"jalr {REG[rd]}, {i_imm():+d}({REG[rs1]})"

    if opcode == 0x63:
        m = {0x0:"beq",0x1:"bne",0x4:"blt",0x5:"bge",0x6:"bltu",0x7:"bgeu"}.get(funct3, "b?")
        return f"{m} {REG[rs1]}, {REG[rs2]}, {b_imm():+d}"

    if opcode == 0x03:
        m = {0x0:"lb",0x1:"lh",0x2:"lw",0x4:"lbu",0x5:"lhu"}.get(funct3, "l?")
        return f"{m} {REG[rd]}, {i_imm():+d}({REG[rs1]})"

    if opcode == 0x23:
        m = {0x0:"sb",0x1:"sh",0x2:"sw"}.get(funct3, "s?")
        return f"{m} {REG[rs2]}, {s_imm():+d}({REG[rs1]})"

    if opcode == 0x13:
        imm = i_imm()
        if funct3 == 0x0: return f"addi {REG[rd]}, {REG[rs1]}, {imm}"
        if funct3 == 0x2: return f"slti {REG[rd]}, {REG[rs1]}, {imm}"
        if funct3 == 0x3: return f"sltiu {REG[rd]}, {REG[rs1]}, {imm}"
        if funct3 == 0x4: return f"xori {REG[rd]}, {REG[rs1]}, {imm}"
        if funct3 == 0x6: return f"ori {REG[rd]}, {REG[rs1]}, {imm}"
        if funct3 == 0x7: return f"andi {REG[rd]}, {REG[rs1]}, {imm}"
        shamt = (instr >> 20) & 0x1F
        if funct3 == 0x1 and funct7 == 0x00: return f"slli {REG[rd]}, {REG[rs1]}, {shamt}"
        if funct3 == 0x5 and funct7 == 0x00: return f"srli {REG[rd]}, {REG[rs1]}, {shamt}"
        if funct3 == 0x5 and funct7 == 0x20: return f"srai {REG[rd]}, {REG[rs1]}, {shamt}"
        return "opimm?"

    if opcode == 0x33:
        if funct3 == 0x0 and funct7 == 0x00: return f"add {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct3 == 0x0 and funct7 == 0x20: return f"sub {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct3 == 0x1 and funct7 == 0x00: return f"sll {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct3 == 0x2 and funct7 == 0x00: return f"slt {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct3 == 0x3 and funct7 == 0x00: return f"sltu {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct3 == 0x4 and funct7 == 0x00: return f"xor {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct3 == 0x5 and funct7 == 0x00: return f"srl {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct3 == 0x5 and funct7 == 0x20: return f"sra {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct3 == 0x6 and funct7 == 0x00: return f"or {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct3 == 0x7 and funct7 == 0x00: return f"and {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        return "op?"

    if opcode == 0x73:
        if instr == 0x00000073: return "ecall"
        if instr == 0x00100073: return "ebreak"
        return "system?"

    return "unknown"

def hex32(x: int) -> str:
    return f"0x{x & 0xFFFFFFFF:08x}"

def get(d: Dict[str, Any], path: str, default=None):
    cur = d
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return default
        cur = cur[part]
    return cur

def load_ndjson(path: str) -> List[Dict[str, Any]]:
    snaps = []
    with open(path, "r") as f:
        for line in f:
            line = line.strip()
            if line:
                snaps.append(json.loads(line))
    return snaps

def stage_line(name: str, pc: int, instr: int) -> str:
    asm = decode_rv32i(instr)
    return f" [{name}] PC: {hex32(pc)} | Instr: {hex32(instr)} | {asm}"

def show_snapshot(s: Dict[str, Any]):
    cycle = int(s.get("cycle", 0))
    pc_if  = int(get(s, "pc.if", 0));   ins_if  = int(get(s, "instr.if", 0))
    pc_id  = int(get(s, "pc.id", 0));   ins_id  = int(get(s, "instr.id", 0))
    pc_ex  = int(get(s, "pc.ex", 0));   ins_ex  = int(get(s, "instr.ex", 0))
    pc_mem = int(get(s, "pc.mem", 0));  ins_mem = int(get(s, "instr.mem", 0))
    pc_wb  = int(get(s, "pc.wb", 0));   ins_wb  = int(get(s, "instr.wb", 0))

    hazard = get(s, "hazard", {}) or {}
    fwd    = get(s, "fwd", {}) or {}
    idinfo = get(s, "id", {}) or {}
    ex     = get(s, "ex", {}) or {}
    mem    = get(s, "mem", {}) or {}
    wb     = get(s, "wb", {}) or {}

    print("\n" + "="*56)
    print(f" ⏱️  CYCLE: {cycle}")
    print("-"*56)
    print(stage_line("IF ", pc_if,  ins_if))
    print(stage_line("ID ", pc_id,  ins_id))
    print(stage_line("EX ", pc_ex,  ins_ex))
    print(stage_line("MEM", pc_mem, ins_mem))
    print(stage_line("WB ", pc_wb,  ins_wb))

    print(f" ID regs: rs1={REG[int(idinfo.get('rs1',0))]} rs2={REG[int(idinfo.get('rs2',0))]} rd={REG[int(idinfo.get('rd',0))]} we={int(idinfo.get('we',0))}")

    pcw = int(hazard.get("pc_write", 0))
    ifs = int(hazard.get("if_stall", 0))
    ids = int(hazard.get("id_stall", 0))
    fl  = int(hazard.get("flush", 0))
    print("-"*56)
    print(f" hazard: pc_write={pcw} if_stall={ifs} id_stall={ids} flush={fl} | fwd: A={int(fwd.get('a_sel',0))} B={int(fwd.get('b_sel',0))}")

    ex_alu = ex.get("alu_result", None)
    ex_rd  = int(ex.get("rd", 0)); ex_we = int(ex.get("we", 0))
    if ex_alu is not None:
        print(f" EX: alu_result={hex32(int(ex_alu))} pc_src={int(ex.get('pc_src',0))} pc_jb={hex32(int(ex.get('pc_jb',0)))} rd={REG[ex_rd]} we={ex_we} mem_rd_op={int(ex.get('mem_rd_op',0))} mem_wr_op={int(ex.get('mem_wr_op',0))} mem_to_reg={int(ex.get('mem_to_reg',0))}")

    mem_addr = int(mem.get("addr", 0))
    mem_wdata = int(mem.get("wdata", 0))
    mem_rdata = int(mem.get("rdata", 0))
    mem_rd_op = int(mem.get("rd_op", 0))
    mem_wr_op = int(mem.get("wr_op", 0))
    mem_rd = int(mem.get("rd", 0)); mem_we = int(mem.get("we", 0))
    print(f" MEM: addr={hex32(mem_addr)} rd_op={mem_rd_op} wr_op={mem_wr_op} wdata={hex32(mem_wdata)} rdata={hex32(mem_rdata)} rd={REG[mem_rd]} we={mem_we} mem_to_reg={int(mem.get('mem_to_reg',0))}")

    wb_rd = int(wb.get("rd", 0)); wb_we = int(wb.get("we", 0))
    wb_wdata = int(wb.get("wdata", 0))
    print(f" WB: rd={REG[wb_rd]} we={wb_we} wdata={hex32(wb_wdata)} check_res={hex32(int(wb.get('check_res',0)))}")
    print("="*56)

@dataclass
class Breakpoint:
    kind: str
    value: int

class LiveClient:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.sock: Optional[socket.socket] = None
        self.f = None
        self.bp: Optional[Breakpoint] = None
        self.record_file = None

    def connect(self):
        print(f"🔄 [PYTHON] Connecting to {self.host}:{self.port}...")
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        s.connect((self.host, self.port))
        self.sock = s
        self.f = s.makefile("r")
        print("✅ [PYTHON] Connected!")

    def close(self):
        try:
            if self.record_file:
                self.record_file.close()
        except Exception:
            pass
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass

    def send(self, line: str):
        assert self.sock is not None
        self.sock.sendall((line.strip() + "\n").encode("utf-8"))

    def recv_snapshot(self) -> Dict[str, Any]:
        assert self.f is not None
        line = self.f.readline()
        if not line:
            raise EOFError("Disconnected")
        s = json.loads(line.strip())
        if self.record_file:
            self.record_file.write(json.dumps(s) + "\n")
            self.record_file.flush()
        return s

    def parse_int(self, s: str) -> int:
        t = s.strip().lower()
        return int(t, 16) if t.startswith("0x") else int(t, 10)

    def replay_mode(self, path: str):
        snaps = load_ndjson(path)
        if not snaps:
            print("No snapshots found.")
            return
        i = 0
        print(f"🎞️  Loaded {len(snaps)} snapshots from {path}")
        while True:
            show_snapshot(snaps[i])
            cmd = input("replay: [n]ext [p]rev [j cycle] [q]uit > ").strip().lower()
            if cmd in ("q", "quit"):
                return
            if cmd in ("n", ""):
                i = min(i + 1, len(snaps) - 1)
            elif cmd == "p":
                i = max(i - 1, 0)
            elif cmd.startswith("j "):
                try:
                    want = int(cmd.split()[1])
                    best = min(range(len(snaps)), key=lambda k: abs(int(snaps[k].get("cycle",0))-want))
                    i = best
                except Exception:
                    pass

    def run(self):
        self.connect()
        try:
            while True:
                snap = self.recv_snapshot()
                show_snapshot(snap)

                cmd = input("👉 step/run/bp/go/record/replay/help/q > ").strip()
                if cmd == "" or cmd == "step":
                    self.send("step")
                elif cmd in ("q", "quit"):
                    self.send("quit")
                    return
                elif cmd.startswith("run "):
                    self.send(cmd)
                elif cmd == "reset":
                    self.send("reset")
                elif cmd == "help":
                    print("""
Commands:
  [Enter] or step           step 1 cycle
  run N                     run N cycles
  bp <kind> <value>         set breakpoint (kind: if_pc id_pc ex_pc mem_pc wb_pc wb_rd mem_addr wb_we)
  clearbp                   clear breakpoint
  go [max]                  run until bp hit (server-side until), default max=10000
  until <kind> <value> [max] direct server-side until
  reset                     reset DUT
  record on <file>          start NDJSON recording
  record off                stop recording
  replay <file>             offline replay from NDJSON
  q                         quit
""".strip())
                elif cmd.startswith("bp "):
                    parts = cmd.split()
                    if len(parts) >= 3:
                        kind = parts[1]
                        value = self.parse_int(parts[2])
                        self.bp = Breakpoint(kind, value)
                        print(f"🧷 Breakpoint set: {kind} == {value}")
                elif cmd == "clearbp":
                    self.bp = None
                    print("🧷 Breakpoint cleared")
                elif cmd.startswith("go"):
                    if not self.bp:
                        print("No breakpoint set. Use: bp <kind> <value>")
                    else:
                        parts = cmd.split()
                        max_steps = int(parts[1]) if len(parts) >= 2 else 10000
                        self.send(f"until {self.bp.kind} {self.bp.value} {max_steps}")
                elif cmd.startswith("until "):
                    self.send(cmd)
                elif cmd.startswith("record on "):
                    path = cmd[len("record on "):].strip()
                    self.record_file = open(path, "w")
                    print(f"📝 Recording ON -> {path}")
                elif cmd == "record off":
                    if self.record_file:
                        self.record_file.close()
                    self.record_file = None
                    print("📝 Recording OFF")
                elif cmd.startswith("replay "):
                    path = cmd[len("replay "):].strip()
                    self.replay_mode(path)
                else:
                    print("Unknown command. Type 'help'.")
        finally:
            self.close()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", default=8888, type=int)
    ap.add_argument("--replay", default=None)
    args = ap.parse_args()

    if args.replay:
        LiveClient(args.host, args.port).replay_mode(args.replay)
        return

    LiveClient(args.host, args.port).run()

if __name__ == "__main__":
    main()
