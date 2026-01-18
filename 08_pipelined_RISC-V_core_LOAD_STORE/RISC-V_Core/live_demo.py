import socket
import json
import re
import argparse
import time
from dataclasses import dataclass
from typing import Optional, Dict, Any, List

REG = [f"x{i}" for i in range(32)]
ALLOWED_BP_KINDS = {"if_pc","id_pc","ex_pc","mem_pc","wb_pc","wb_rd","mem_addr","wb_we"}

# --------------------------
# RISC-V decode helpers
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
    def s_imm():
        imm = ((instr >> 7) & 0x1F) | (((instr >> 25) & 0x7F) << 5)
        return sign_extend(imm, 12)
    def b_imm():
        imm = (((instr >> 8) & 0xF) << 1)               | (((instr >> 25) & 0x3F) << 5)               | (((instr >> 7) & 0x1) << 11)               | (((instr >> 31) & 0x1) << 12)
        return sign_extend(imm, 13)
    def u_imm(): return instr & 0xFFFFF000
    def j_imm():
        imm = (((instr >> 21) & 0x3FF) << 1)               | (((instr >> 20) & 0x1) << 11)               | (((instr >> 12) & 0xFF) << 12)               | (((instr >> 31) & 0x1) << 20)
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

# --------------------------
# Pretty printing
# --------------------------
def hex32(x: int) -> str:
    return f"0x{x & 0xFFFFFFFF:08x}"

def get(d: Dict[str, Any], path: str, default=None):
    cur: Any = d
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






# --------------------------
# VISUALIZATION HELPERS
# --------------------------
class Layout:
    # ASCII Box Drawing
    HL = "─"
    VL = "│"
    TL = "┌"; TR = "┐"; BL = "└"; BR = "┘"
    T_DOWN = "┬"; T_UP = "┴"; T_RIGHT = "├"; T_LEFT = "┤"; CROSS = "┼"

    # Exact Column Widths
    W_STAGE = 4
    W_STATUS = 12
    W_PC = 12
    W_INSTR = 32
    W_DETAILS = 42

class Colors:
    RESET = "\033[0m"
    BOLD = "\033[1m"

    # Background Colors (Black Text)
    IF  = "\033[48;5;229m\033[38;5;0m"  # Yellow
    ID  = "\033[48;5;120m\033[38;5;0m"  # Green
    EX  = "\033[48;5;117m\033[38;5;0m"  # Blue
    MEM = "\033[48;5;213m\033[38;5;0m"  # Pink
    WB  = "\033[48;5;159m\033[38;5;0m"  # Cyan

    # Foreground Colors
    GREEN = "\033[92m"
    RED = "\033[91m"
    ORANGE = "\033[38;5;208m"
    BLUE = "\033[94m"
    DIM = "\033[90m"

def strip_ansi(text):
    """Removes invisible color codes to calculate real length."""
    ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
    return ansi_escape.sub('', str(text))

def pad(text, width):
    """Pads text with spaces based on VISIBLE length, not raw length."""
    s = str(text)
    visible_len = len(strip_ansi(s))
    if visible_len >= width:
        # Truncate if too long (keeping colors is hard, simple slice)
        return s[:width]
    return s + " " * (width - visible_len)

def fmt_hex(val):
    if val is None: return "-"
    return f"0x{int(val):08x}"

def get_fwd_str(sel):
    sel = int(sel)
    if sel == 0: return ""
    if sel == 1: return f"{Colors.ORANGE}FWD(MEM){Colors.RESET}"
    if sel == 2: return f"{Colors.ORANGE}FWD(WB){Colors.RESET}"
    return f"FWD({sel})"

# --------------------------
# DASHBOARD RENDERER
# --------------------------
def show_snapshot(s: Dict[str, Any]):
    cycle = int(s.get("cycle", 0))
    hazard = get(s, "hazard", {}) or {}
    fwd    = get(s, "fwd", {}) or {}
    id_info = get(s, "id", {}) or {}
    ex     = get(s, "ex", {}) or {}
    mem    = get(s, "mem", {}) or {}
    wb     = get(s, "wb", {}) or {}

    # Control Signals
    pc_write = int(hazard.get("pc_write", 1))
    if_stall = int(hazard.get("if_stall", 0))
    id_stall = int(hazard.get("id_stall", 0))
    flush    = int(hazard.get("flush", 0))

    # Forwarding Detection
    fwd_a = int(fwd.get("a_sel", 0))
    fwd_b = int(fwd.get("b_sel", 0))

    # --- 1. HEADER ---
    # Calculate Total Width (Columns + 1 space padding each side + vertical bars)
    # Structure: "│ " + COL + " │ " + COL ...
    total_w = (Layout.W_STAGE + Layout.W_STATUS + Layout.W_PC +
               Layout.W_INSTR + Layout.W_DETAILS + 13)

    print("\n" + Colors.BOLD + Layout.TL + Layout.HL * (total_w - 2) + Layout.TR + Colors.RESET)

    status_led = f"{Colors.GREEN}● RUNNING{Colors.RESET}" if pc_write else f"{Colors.RED}● STALLED{Colors.RESET}"
    header_txt = f" ⏱️  CYCLE: {pad(str(cycle), 10)} | {status_led}"
    print(f"{Layout.VL} {pad(header_txt, total_w - 4)} {Layout.VL}")

    # --- 2. TABLE HEADER ---
    sep_top = (Layout.T_LEFT + Layout.HL * (Layout.W_STAGE + 2) + Layout.CROSS +
               Layout.HL * (Layout.W_STATUS + 2) + Layout.CROSS +
               Layout.HL * (Layout.W_PC + 2) + Layout.CROSS +
               Layout.HL * (Layout.W_INSTR + 2) + Layout.CROSS +
               Layout.HL * (Layout.W_DETAILS + 2) + Layout.T_RIGHT)

    print(sep_top)

    # Header Row
    row_str = (f"{Layout.VL} {pad('STG', Layout.W_STAGE)} {Layout.VL} "
               f"{pad('STATUS', Layout.W_STATUS)} {Layout.VL} "
               f"{pad('PC', Layout.W_PC)} {Layout.VL} "
               f"{pad('INSTRUCTION', Layout.W_INSTR)} {Layout.VL} "
               f"{pad('DETAILS / ACTIVITY', Layout.W_DETAILS)} {Layout.VL}")
    print(row_str)
    print(sep_top)

    # --- Helper to Print Rows ---
    def print_row(name, color, pc_key, instr_key, detail_txt):
        pc_val = int(get(s, pc_key, 0))
        ins_val = int(get(s, instr_key, 0))
        asm = decode_rv32i(ins_val)

        # Status Logic
        status = f"{Colors.GREEN}OK{Colors.RESET}"
        if name == "IF" and if_stall: status = f"{Colors.RED}STALL{Colors.RESET}"
        if name == "ID" and id_stall: status = f"{Colors.RED}STALL{Colors.RESET}"
        if flush and name in ["ID", "EX"]: status = f"{Colors.ORANGE}FLUSH{Colors.RESET}"

        # Construct Row
        stage_cell = f"{color} {pad(name, Layout.W_STAGE-1)}{Colors.RESET}"

        print(f"{Layout.VL} {stage_cell} {Layout.VL} "
              f"{pad(status, Layout.W_STATUS)} {Layout.VL} "
              f"{pad(fmt_hex(pc_val), Layout.W_PC)} {Layout.VL} "
              f"{pad(asm, Layout.W_INSTR)} {Layout.VL} "
              f"{pad(detail_txt, Layout.W_DETAILS)} {Layout.VL}")

    # --- 3. STAGE ROWS ---

    # IF
    print_row("IF", Colors.IF, "pc.if", "instr.if", "Fetch Instruction")

    # ID
    rs1 = int(id_info.get("rs1", 0)); rs2 = int(id_info.get("rs2", 0)); rd = int(id_info.get("rd", 0))
    id_det = f"rs1:x{rs1:<2} rs2:x{rs2:<2} -> rd:x{rd}"
    print_row("ID", Colors.ID, "pc.id", "instr.id", id_det)

    # EX
    alu = int(ex.get("alu_result", 0))
    # Build EX Detail with Forwarding Info
    ex_parts = [f"Res:{Colors.BOLD}{fmt_hex(alu)}{Colors.RESET}"]
    if fwd_a: ex_parts.append(f"A:{get_fwd_str(fwd_a)}")
    if fwd_b: ex_parts.append(f"B:{get_fwd_str(fwd_b)}")

    # Check Jumps
    if int(ex.get("pc_src", 0)):
        tgt = int(ex.get("pc_jb", 0))
        ex_parts.append(f"{Colors.ORANGE}JMP->{fmt_hex(tgt)}{Colors.RESET}")

    print_row("EX", Colors.EX, "pc.ex", "instr.ex", " ".join(ex_parts))

    # MEM
    addr = int(mem.get("addr", 0)); wdata = int(mem.get("wdata", 0))
    we = int(mem.get("we", 0))
    mem_det = f"{Colors.DIM}Idle{Colors.RESET}"
    if we: mem_det = f"{Colors.ORANGE}WR{Colors.RESET} M[{fmt_hex(addr)}]={fmt_hex(wdata)}"
    elif mem.get("mem_rd_op", 0): mem_det = f"{Colors.BLUE}RD{Colors.RESET} M[{fmt_hex(addr)}]"
    print_row("MEM", Colors.MEM, "pc.mem", "instr.mem", mem_det)

    # WB
    wb_rd = int(wb.get("rd", 0)); wb_wd = int(wb.get("wdata", 0)); wb_we = int(wb.get("we", 0))
    wb_det = ""
    if wb_we and wb_rd != 0: wb_det = f"{Colors.GREEN}Write{Colors.RESET} x{wb_rd} = {fmt_hex(wb_wd)}"
    print_row("WB", Colors.WB, "pc.wb", "instr.wb", wb_det)

    # --- 4. FOOTER ---
    print(Layout.BL + Layout.HL * (total_w - 2) + Layout.BR)

    # Hazards Summary
    haz_list = []
    if if_stall: haz_list.append(f"{Colors.RED}[IF_STALL]{Colors.RESET}")
    if id_stall: haz_list.append(f"{Colors.RED}[ID_STALL]{Colors.RESET}")
    if flush:    haz_list.append(f"{Colors.ORANGE}[FLUSH]{Colors.RESET}")
    # FIX: Explicitly list Forwarding as a hazard resolution
    if fwd_a or fwd_b: haz_list.append(f"{Colors.BLUE}[DATA_FWD]{Colors.RESET}")

    haz_str = " ".join(haz_list) if haz_list else f"{Colors.DIM}None{Colors.RESET}"
    print(f" {Colors.BOLD}⚡ HAZARDS / EVENTS:{Colors.RESET} {haz_str}")
    print(Layout.HL * total_w)
# --------------------------
# Live protocol client
# --------------------------
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
        self._last_snapshot: Optional[Dict[str, Any]] = None

    def connect(self):
        print(f"🔄 [PYTHON] Connecting to {self.host}:{self.port}...")
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        # Retry so you can start Python before sbt is ready
        while True:
            try:
                s.connect((self.host, self.port))
                break
            except (ConnectionRefusedError, OSError):
                print("   ... waiting for Chisel server ...")
                time.sleep(0.5)

        self.sock = s
        self.f = s.makefile("r", encoding="utf-8")
        print("✅ [PYTHON] Connected!")

    def close(self):
        try:
            if self.record_file:
                self.record_file.close()
        except Exception:
            pass
        try:
            if self.f:
                self.f.close()
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
        while True:
            line = self.f.readline()
            if not line:
                raise EOFError("Disconnected")
            line = line.strip()
            if not line:
                continue
            try:
                s = json.loads(line)
                self._last_snapshot = s
                if self.record_file:
                    self.record_file.write(json.dumps(s) + "\n")
                    self.record_file.flush()
                return s
            except json.JSONDecodeError:
                print(f"⚠️ Bad JSON from server: {line[:120]}")
                continue

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

    def bp_hit(self, snap: Dict[str, Any]) -> bool:
        if not self.bp:
            return False
        k, v = self.bp.kind, self.bp.value
        if k == "if_pc":    return int(get(snap, "pc.if", 0)) == v
        if k == "id_pc":    return int(get(snap, "pc.id", 0)) == v
        if k == "ex_pc":    return int(get(snap, "pc.ex", 0)) == v
        if k == "mem_pc":   return int(get(snap, "pc.mem", 0)) == v
        if k == "wb_pc":    return int(get(snap, "pc.wb", 0)) == v
        if k == "wb_rd":    return int(get(snap, "wb.rd", 0)) == v
        if k == "wb_we":    return int(get(snap, "wb.we", 0)) == v
        if k == "mem_addr": return int(get(snap, "mem.addr", 0)) == v
        return False

    def run_chunked(self, total_cycles: int, chunk: int = 50) -> Dict[str, Any]:
        """
        Runs total_cycles cycles by sending repeated 'run <chunk>' commands.
        Returns the last snapshot received.
        """
        if total_cycles <= 0:
            return self._last_snapshot or self.recv_snapshot()

        done = 0
        last = self._last_snapshot
        try:
            while done < total_cycles:
                n = min(chunk, total_cycles - done)
                self.send(f"run {n}")
                last = self.recv_snapshot()
                show_snapshot(last)
                done += n
        except KeyboardInterrupt:
            print("\n🟧 Interrupted. You can continue typing commands.")
        return last or self.recv_snapshot()

    def go_until_bp(self, max_cycles: int = 10000, chunk: int = 50) -> Optional[Dict[str, Any]]:
        """
        Runs until breakpoint hit (client-side check) or max_cycles reached.
        max_cycles: maximum number of cycles to execute while searching for breakpoint.
        chunk: number of cycles per network round-trip.
        """
        if not self.bp:
            print("No breakpoint set. Use: bp <kind> <value>   or   bp <value> (defaults to if_pc)")
            return None

        if self.bp.kind.endswith("_pc") and (self.bp.value % 4 != 0):
            print("⚠️ PC breakpoints are usually 4-byte aligned. This may never hit.")

        print(f"⏩ go: until {self.bp.kind} == {hex32(self.bp.value)} (max {max_cycles} cycles, chunk {chunk})  [Ctrl+C to interrupt]")

        steps = 0
        last = self._last_snapshot
        try:
            while steps < max_cycles:
                n = min(chunk, max_cycles - steps)
                self.send(f"run {n}")
                last = self.recv_snapshot()
                show_snapshot(last)
                steps += n
                if self.bp_hit(last):
                    print("🟢 Breakpoint HIT")
                    return last
            print("🟡 Breakpoint TIMEOUT (max cycles reached)")
            return last
        except KeyboardInterrupt:
            print("\n🟧 Interrupted go. You can continue typing commands.")
            return last

    def run(self):
        self.connect()
        try:
            snap = self.recv_snapshot()
            self._last_snapshot = snap

            while True:
                show_snapshot(snap)

                cmd = input("👉 step/run/bp/go/record/replay/help/q > ").strip()

                # Ignore arrow-key escape garbage
                if "\x1b" in cmd:
                    continue

                # Aliases / common typos: b / break / bq -> bp
                if cmd.startswith("bq "):
                    cmd = "bp " + cmd[len("bq "):]
                elif cmd.startswith("break "):
                    cmd = "bp " + cmd[len("break "):]
                elif cmd.startswith("b "):
                    cmd = "bp " + cmd[len("b "):]

                # Shortcut: "<kind> <value>" -> "bp <kind> <value>"
                parts = cmd.split()
                if len(parts) == 2 and parts[0] in ALLOWED_BP_KINDS:
                    cmd = f"bp {parts[0]} {parts[1]}"
                    parts = cmd.split()

                if cmd == "" or cmd == "step":
                    self.send("step")
                    snap = self.recv_snapshot()

                elif cmd in ("q", "quit"):
                    self.send("quit")
                    return

                elif cmd.startswith("run"):
                    # run N [chunk]
                    parts = cmd.split()
                    n = int(parts[1]) if len(parts) >= 2 else 1
                    chunk = int(parts[2]) if len(parts) >= 3 else 50
                    chunk = max(1, chunk)
                    print(f"⏩ run: {n} cycles (chunk {chunk})  [Ctrl+C to interrupt]")
                    snap = self.run_chunked(n, chunk=chunk)

                elif cmd == "reset":
                    self.send("reset")
                    snap = self.recv_snapshot()

                elif cmd == "help":
                    print("""
Commands:
  [Enter] or step
      Step the CPU by 1 clock cycle.

  run N [chunk]
      Run N cycles total.
      chunk (optional) controls how many cycles are executed per network round-trip.
      Example: 'run 200 50' runs 200 cycles, receiving a snapshot every 50 cycles.

  bp <value>
      Set breakpoint on IF stage PC (if_pc) to <value>.
      Example: 'bp 0x20' breaks when IF PC == 0x00000020.

  bp <kind> <value>
      Breakpoint on a specific signal.
      kind in: if_pc id_pc ex_pc mem_pc wb_pc wb_rd wb_we mem_addr
      Example: 'bp mem_addr 0x00000004'

  <kind> <value>
      Shortcut for bp. Example: 'if_pc 0x20' is the same as 'bp if_pc 0x20'.

  go [max_cycles] [chunk]
      Run until breakpoint hit (checked client-side) or max_cycles reached.
      max_cycles default=10000, chunk default=50.
      Example: 'go 200' runs up to 200 cycles searching for the breakpoint.

  clearbp
      Clear current breakpoint.

  until <kind> <value> [max]
      Server-side until (can look frozen for long runs). Prefer 'go'.

  record on <file>
      Start recording snapshots to NDJSON. Writes the current snapshot immediately.

  record off
      Stop recording.

  replay <file>
      Replay an NDJSON recording offline.

  q
      Quit.
""".strip())

                elif cmd.startswith("bp "):
                    parts = cmd.split()
                    # Allow: bp <value> (defaults to if_pc)
                    if len(parts) == 2:
                        kind = "if_pc"
                        value = self.parse_int(parts[1])
                        self.bp = Breakpoint(kind, value)
                        print(f"🧷 Breakpoint set: {kind} == {hex32(value)}")
                    elif len(parts) >= 3:
                        kind = parts[1]
                        value = self.parse_int(parts[2])
                        if kind not in ALLOWED_BP_KINDS:
                            print(f"Unknown breakpoint kind '{kind}'. Try one of: {sorted(ALLOWED_BP_KINDS)}")
                        else:
                            self.bp = Breakpoint(kind, value)
                            print(f"🧷 Breakpoint set: {kind} == {hex32(value)}")
                    else:
                        print("Usage: bp <value>  OR  bp <kind> <value>")

                elif cmd == "clearbp":
                    self.bp = None
                    print("🧷 Breakpoint cleared")

                elif cmd.startswith("go"):
                    parts = cmd.split()
                    max_cycles = int(parts[1]) if len(parts) >= 2 else 10000
                    chunk = int(parts[2]) if len(parts) >= 3 else 50
                    chunk = max(1, chunk)
                    last = self.go_until_bp(max_cycles=max_cycles, chunk=chunk)
                    if last is not None:
                        snap = last

                elif cmd.startswith("until "):
                    self.send(cmd)
                    snap = self.recv_snapshot()

                elif cmd.startswith("record on "):
                    path = cmd[len("record on "):].strip()
                    self.record_file = open(path, "w", encoding="utf-8")
                    print(f"📝 Recording ON -> {path}")
                    # Write current snapshot immediately so file isn't empty
                    if self._last_snapshot is not None:
                        self.record_file.write(json.dumps(self._last_snapshot) + "\n")
                        self.record_file.flush()

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