
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