import socket
import json
import re
import argparse
import time
import sys
import tty
import termios
import subprocess
import os
import signal
import atexit
from dataclasses import dataclass
from typing import Optional, Dict, Any, List

# --------------------------
# CONFIG & CONSTANTS
# --------------------------
REG = [f"x{i}" for i in range(32)]
ALLOWED_BP_KINDS = {"if_pc","id_pc","ex_pc","mem_pc","wb_pc","wb_rd","mem_addr","wb_we"}

# --------------------------
# RISC-V DECODER
# --------------------------
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
    def s_imm(): return sign_extend(((instr >> 7) & 0x1F) | (((instr >> 25) & 0x7F) << 5), 12)
    def b_imm(): return sign_extend((((instr >> 8) & 0xF) << 1) | (((instr >> 25) & 0x3F) << 5) | (((instr >> 7) & 0x1) << 11) | (((instr >> 31) & 0x1) << 12), 13)
    def u_imm(): return instr & 0xFFFFF000
    def j_imm(): return sign_extend((((instr >> 21) & 0x3FF) << 1) | (((instr >> 20) & 0x1) << 11) | (((instr >> 12) & 0xFF) << 12) | (((instr >> 31) & 0x1) << 20), 21)

    if instr == 0x00000013: return "nop"
    if opcode == 0x37: return f"lui {REG[rd]}, {u_imm():#x}"
    if opcode == 0x17: return f"auipc {REG[rd]}, {u_imm():#x}"
    if opcode == 0x6F: return f"jal {REG[rd]}, {j_imm():+d}"
    if opcode == 0x67 and funct3 == 0x0: return f"jalr {REG[rd]}, {i_imm():+d}({REG[rs1]})"
    if opcode == 0x63:
        m = {0x0:"beq",0x1:"bne",0x4:"blt",0x5:"bge",0x6:"bltu",0x7:"bgeu"}.get(funct3,"b?")
        return f"{m} {REG[rs1]}, {REG[rs2]}, {b_imm():+d}"
    if opcode == 0x03:
        m = {0x0:"lb",0x1:"lh",0x2:"lw",0x4:"lbu",0x5:"lhu"}.get(funct3,"l?")
        return f"{m} {REG[rd]}, {i_imm():+d}({REG[rs1]})"
    if opcode == 0x23:
        m = {0x0:"sb",0x1:"sh",0x2:"sw"}.get(funct3,"s?")
        return f"{m} {REG[rs2]}, {s_imm():+d}({REG[rs1]})"
    if opcode == 0x13:
        imm = i_imm()
        if funct3 == 0x0: return f"addi {REG[rd]}, {REG[rs1]}, {imm}"
        if funct3 == 0x7: return f"andi {REG[rd]}, {REG[rs1]}, {imm}"
        if funct3 == 0x6: return f"ori {REG[rd]}, {REG[rs1]}, {imm}"
        if funct3 == 0x4: return f"xori {REG[rd]}, {REG[rs1]}, {imm}"
        return "opimm?"
    if opcode == 0x33:
        if funct7 == 0x00:
            m = {0x0:"add",0x1:"sll",0x2:"slt",0x3:"sltu",0x4:"xor",0x5:"srl",0x6:"or",0x7:"and"}.get(funct3,"op?")
            return f"{m} {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
        if funct7 == 0x20:
            m = {0x0:"sub",0x5:"sra"}.get(funct3,"op?")
            return f"{m} {REG[rd]}, {REG[rs1]}, {REG[rs2]}"
    return "unknown"

# --------------------------
# AUTO-LAUNCHER
# --------------------------
SERVER_PROCESS = None

def cleanup():
    """Restores terminal and kills server."""
    global SERVER_PROCESS
    print("\033[?25h") # Show Cursor
    os.system("stty sane")
    if SERVER_PROCESS:
        try:
            os.killpg(os.getpgid(SERVER_PROCESS.pid), signal.SIGTERM)
        except Exception: pass
        SERVER_PROCESS = None

def ensure_server_running(host, port):
    global SERVER_PROCESS
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        if s.connect_ex((host, port)) == 0: return

    print(f"🚀 Launching Chisel Simulation on port {port}...")
    print("   (This takes 10-20 seconds to compile...)")
    log_file = open("chisel_server.log", "w")
    cmd = ["sbt", "testOnly PipelinedRV32I_Tester.LivePipelineTest"]

    SERVER_PROCESS = subprocess.Popen(
        cmd,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        preexec_fn=os.setsid
    )
    atexit.register(cleanup)
    print("⏳ Waiting 15 seconds for warm up...")
    time.sleep(15)

# --------------------------
# VISUALIZATION
# --------------------------
class Layout:
    HL="─"; VL="│"; TL="┌"; TR="┐"; BL="└"; BR="┘"
    T_LEFT="├"; T_RIGHT="┤"; CROSS="┼"
    W_STAGE=4; W_STATUS=12; W_PC=12; W_INSTR=32; W_DETAILS=42

class Colors:
    RESET="\033[0m"; BOLD="\033[1m"; DIM="\033[90m"
    IF="\033[48;5;229m\033[38;5;0m"; ID="\033[48;5;120m\033[38;5;0m"
    EX="\033[48;5;117m\033[38;5;0m"; MEM="\033[48;5;213m\033[38;5;0m"
    WB="\033[48;5;159m\033[38;5;0m"
    GREEN="\033[92m"; RED="\033[91m"; ORANGE="\033[38;5;208m"; BLUE="\033[94m"

def strip_ansi(text):
    return re.sub(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])', '', str(text))

def pad(text, width):
    s = str(text)
    vis = len(strip_ansi(s))
    if vis >= width: return s[:width]
    return s + " " * (width - vis)

def fmt_hex(val): return f"0x{int(val):08x}" if val is not None else "-"

def get(d, path, default=0):
    cur = d
    for p in path.split("."):
        if not isinstance(cur, dict) or p not in cur: return default
        cur = cur[p]
    return cur

def show_snapshot(s: Dict[str, Any]):
    # Unpack
    cycle = int(s.get("cycle", 0))
    haz = get(s,"hazard",{}); fwd = get(s,"fwd",{})

    # Header
    w_tot = Layout.W_STAGE + Layout.W_STATUS + Layout.W_PC + Layout.W_INSTR + Layout.W_DETAILS + 13
    print("\n" + Colors.BOLD + Layout.TL + Layout.HL*(w_tot-2) + Layout.TR + Colors.RESET + "\033[K")

    stat = f"{Colors.GREEN}● RUNNING{Colors.RESET}" if haz.get("pc_write",1) else f"{Colors.RED}● STALLED{Colors.RESET}"
    head = f" ⏱️  CYCLE: {pad(str(cycle), 10)} | {stat}"
    print(f"{Layout.VL} {pad(head, w_tot-4)} {Layout.VL}\033[K")

    sep = (Layout.T_LEFT + Layout.HL*(Layout.W_STAGE+2) + Layout.CROSS +
           Layout.HL*(Layout.W_STATUS+2) + Layout.CROSS + Layout.HL*(Layout.W_PC+2) + Layout.CROSS +
           Layout.HL*(Layout.W_INSTR+2) + Layout.CROSS + Layout.HL*(Layout.W_DETAILS+2) + Layout.T_RIGHT)
    print(sep + "\033[K")

    # Rows
    def p_row(nm, col, pc, ins, det):
        pc_v = int(get(s, pc)); ins_v = int(get(s, ins))
        asm = decode_rv32i(ins_v)
        st = f"{Colors.GREEN}OK{Colors.RESET}"
        if nm=="IF" and haz.get("if_stall"): st=f"{Colors.RED}STALL{Colors.RESET}"
        if nm=="ID" and haz.get("id_stall"): st=f"{Colors.RED}STALL{Colors.RESET}"
        if haz.get("flush") and nm in ["ID","EX"]: st=f"{Colors.ORANGE}FLUSH{Colors.RESET}"

        row = (f"{Layout.VL} {col} {pad(nm, Layout.W_STAGE-1)}{Colors.RESET} {Layout.VL} "
               f"{pad(st, Layout.W_STATUS)} {Layout.VL} {pad(fmt_hex(pc_v), Layout.W_PC)} {Layout.VL} "
               f"{pad(asm, Layout.W_INSTR)} {Layout.VL} {pad(det, Layout.W_DETAILS)} {Layout.VL}")
        print(row + "\033[K")

    # Data
    id_d = f"rs1:x{get(s,'id.rs1')} rs2:x{get(s,'id.rs2')} -> rd:x{get(s,'id.rd')}"

    ex_d = [f"Res:{Colors.BOLD}{fmt_hex(get(s,'ex.alu_result'))}{Colors.RESET}"]
    if fwd.get("a_sel"): ex_d.append(f"A:FWD")
    if fwd.get("b_sel"): ex_d.append(f"B:FWD")
    if get(s,"ex.pc_src"): ex_d.append(f"{Colors.ORANGE}JMP{Colors.RESET}")

    mem_we = get(s,"mem.we")
    mem_d = f"{Colors.ORANGE}WR{Colors.RESET} [{fmt_hex(get(s,'mem.addr'))}]" if mem_we else f"{Colors.DIM}Idle{Colors.RESET}"

    wb_we = get(s,"wb.we"); wb_rd = get(s,"wb.rd")
    wb_d = f"{Colors.GREEN}Wr{Colors.RESET} x{wb_rd}={fmt_hex(get(s,'wb.wdata'))}" if (wb_we and wb_rd) else ""

    p_row("IF", Colors.IF, "pc.if", "instr.if", "Fetch")
    p_row("ID", Colors.ID, "pc.id", "instr.id", id_d)
    p_row("EX", Colors.EX, "pc.ex", "instr.ex", " ".join(ex_d))
    p_row("MEM", Colors.MEM, "pc.mem", "instr.mem", mem_d)
    p_row("WB", Colors.WB, "pc.wb", "instr.wb", wb_d)

    print(Layout.BL + Layout.HL*(w_tot-2) + Layout.BR + "\033[K")

    # Hazards
    h_list = []
    if haz.get("if_stall"): h_list.append(f"{Colors.RED}[IF_STALL]{Colors.RESET}")
    if haz.get("id_stall"): h_list.append(f"{Colors.RED}[ID_STALL]{Colors.RESET}")
    if haz.get("flush"): h_list.append(f"{Colors.ORANGE}[FLUSH]{Colors.RESET}")
    if fwd.get("a_sel") or fwd.get("b_sel"): h_list.append(f"{Colors.BLUE}[DATA_FWD]{Colors.RESET}")

    h_str = " ".join(h_list) if h_list else f"{Colors.DIM}None{Colors.RESET}"
    print(f" {Colors.BOLD}⚡ HAZARDS / EVENTS:{Colors.RESET} {h_str}\033[K")

# --------------------------
# CLIENT
# --------------------------
@dataclass
class Breakpoint:
    kind: str
    value: int

class LiveClient:
    def __init__(self, host: str, port: int):
        self.host, self.port = host, port
        self.sock, self.f = None, None
        self.history, self.view_idx = [], -1
        self.record_file = None
        self.bp = None

    # --- LINUX KEYBOARD ---
    def get_key(self):
        fd = sys.stdin.fileno()
        old = termios.tcgetattr(fd)
        try:
            tty.setraw(fd)
            ch = sys.stdin.read(1)
            if ch == '\x1b':
                seq = sys.stdin.read(2)
                if seq == '[A': return 'UP'
                if seq == '[B': return 'DOWN'
                if seq == '[C': return 'RIGHT'
                if seq == '[D': return 'LEFT'
                if seq == '[5': sys.stdin.read(1); return 'PGUP'
                if seq == '[6': sys.stdin.read(1); return 'PGDN'
                return 'ESC'
            return ch
        finally:
            termios.tcsetattr(fd, termios.TCSADRAIN, old)

    def connect(self):
        print("\033[H\033[J") # Clear
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        while True:
            try:
                s.connect((self.host, self.port))
                break
            except: time.sleep(0.5)
        self.sock = s
        self.f = s.makefile("r", encoding="utf-8")

    def close(self):
        try:
            if self.record_file: self.record_file.close()
            if self.f: self.f.close()
            if self.sock: self.sock.close()
        except: pass

    def send(self, msg): self.sock.sendall((msg.strip()+"\n").encode())

    def recv_snapshot(self):
        while True:
            line = self.f.readline()
            if not line: raise EOFError
            try:
                s = json.loads(line)
                self.history.append(s)
                self.view_idx = len(self.history)-1
                if self.record_file: self.record_file.write(json.dumps(s)+"\n")
                return s
            except: continue

    # --- SMART NAVIGATION ---
    def step_delta(self, n):
        target = self.view_idx + n
        live_head = len(self.history) - 1

        # 1. Going Backward or Staying in History
        if target <= live_head:
            self.view_idx = max(0, target)
            return

        # 2. Going Forward into the Future (Need Simulation)
        needed = target - live_head
        # Jump view to current live head first
        self.view_idx = live_head

        # Run 'needed' steps on server
        for _ in range(needed):
            self.send("step")
            self.recv_snapshot()

    def handle_command_input(self):
        print("\033[?25h") # Show cursor
        os.system("stty sane")
        try:
            cmd = input(f"\n{Colors.BOLD}COMMAND > {Colors.RESET}").strip()
            # FIX: Lowercase command handling
            cmd_lower = cmd.lower()

            if cmd_lower.startswith("record on "):
                path = cmd.split(" ", 2)[2]
                self.record_file = open(path, "w")
                print(f"Recording to {path}")

            elif cmd_lower == "record off":
                if self.record_file: self.record_file.close()
                self.record_file = None
                print("Recording stopped")

            # FIX: Added Reset / Clear
            elif cmd_lower in ["reset", "clear"]:
                self.send("reset")
                self.history.clear()
                self.recv_snapshot()
                print("History Cleared & Reset")

            elif cmd_lower.startswith("bp "):
                parts = cmd.split()
                if len(parts) > 1:
                    val = int(parts[-1], 16) if parts[-1].startswith("0x") else int(parts[-1])
                    kind = parts[1] if len(parts) > 2 else "if_pc"
                    self.bp = Breakpoint(kind, val)
                    print(f"Breakpoint set: {kind}={val:#x}")

            elif cmd_lower in ["help", "h"]:
                self.show_help()

            # Pause to let user read status
            if cmd: time.sleep(0.8)

        except Exception as e:
            print(f"Error: {e}")
            time.sleep(1)

    def show_help(self):
        print(f"\n{Colors.BOLD}--- HELP ---{Colors.RESET}")
        print(" [Arrows]     Step Back/Forward (1 cycle)")
        print(" [PgUp/Dn]    Jump Back/Forward (100 cycles)")
        print(" [Up/Down]    Jump Back/Forward (10 cycles)")
        print(" [Home]       Go to Start")
        print(" [Enter]      Jump to Live Head")
        print(" [:]          Enter Command Mode")
        print(" [q]          Quit")
        print("\nCommands (type ':' first):")
        print("  bp <val>        Set Breakpoint (IF PC)")
        print("  record on <f>   Start recording to file")
        print("  reset / clear   Reset Processor & History")
        input("\nPress Enter to return...")

    def run(self):
        self.connect()
        try:
            self.recv_snapshot() # Get Cycle 0
            while True:
                # 1. Render
                print("\033[H", end="") # Top Left
                self.view_idx = max(0, min(self.view_idx, len(self.history)-1))
                show_snapshot(self.history[self.view_idx])

                live = len(self.history)-1
                is_live = (self.view_idx == live)

                # Status Bar
                print("\033[K")
                if is_live: print(f"{Colors.GREEN} 🟢 LIVE HEAD {Colors.RESET} | Cycle: {self.history[-1].get('cycle')}\033[K")
                else:       print(f"{Colors.ORANGE} ⏪ HISTORY {Colors.RESET} | View: {self.view_idx}/{live}\033[K")

                print(f"{Colors.DIM} [⬆️/⬇️  +/-10] [➡️ /⬅️  +/-1] [Enter=Live] [:=Cmd] [h=Help] [q=Quit]{Colors.RESET}\033[K")

                # 2. Input
                key = self.get_key()

                if key=='q': break
                elif key=='\r' or key=='\n': self.view_idx = live # Enter -> Live
                elif key==':' or key == ';': self.handle_command_input()
                elif key=='h' or key == '?': self.handle_command_input() # abuse cmd handler to show help

                elif key=='RIGHT': self.step_delta(1)
                elif key=='LEFT':  self.step_delta(-1)

                elif key=='UP':    self.step_delta(10)
                elif key=='DOWN':  self.step_delta(-10)

                elif key=='PGUP':  self.step_delta(100)
                elif key=='PGDN':  self.step_delta(-100)

                elif key=='HOME': self.view_idx = 0

        finally:
            self.close()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", default=8888, type=int)
    args = ap.parse_args()

    ensure_server_running(args.host, args.port)
    LiveClient(args.host, args.port).run()

if __name__ == "__main__":
    main()