
import re
from typing import Dict, Any
from .decoder import decode_rv32i

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

    # Data Construction
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

    # Footer
    h_list = []
    if haz.get("if_stall"): h_list.append(f"{Colors.RED}[IF_STALL]{Colors.RESET}")
    if haz.get("id_stall"): h_list.append(f"{Colors.RED}[ID_STALL]{Colors.RESET}")
    if haz.get("flush"): h_list.append(f"{Colors.ORANGE}[FLUSH]{Colors.RESET}")
    if fwd.get("a_sel") or fwd.get("b_sel"): h_list.append(f"{Colors.BLUE}[DATA_FWD]{Colors.RESET}")

    h_str = " ".join(h_list) if h_list else f"{Colors.DIM}None{Colors.RESET}"
    print(f" {Colors.BOLD}⚡ HAZARDS / EVENTS:{Colors.RESET} {h_str}\033[K")