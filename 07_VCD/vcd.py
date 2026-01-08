from vcdvcd import VCDVCD
import collections

vcd = VCDVCD("HazardDetectionRV32I.vcd", signals=["pc_if", "pc_id", "pc_ex", "pc_mem", "pc_wb"], store_tvs=True)

# Reverse map: pc → instruction label (optional, if you know them)
pc_to_instr = {
    0x00: "0x00000013",  # NOP
    0x04: "0x00400093",  # ADDI x1, x0, 4
    0x08: "0x00500113",  # ADDI x2, x0, 5
    # ...
}

# Create a structure: instr → list of stage per cycle
instr_timeline = collections.defaultdict(lambda: {})

# Get time steps
times = sorted(set(t for sig in vcd.data.values() for t, _ in sig.tv))

# For each stage
stages = ["pc_if", "pc_id", "pc_ex", "pc_mem", "pc_wb"]
for stage in stages:
    tv = vcd[stage].tv  # [(time, value)]

    last_value = None
    for t, val in tv:
        pc = int(val, 16)
        instr = pc_to_instr.get(pc, f"0x{pc:08x}")
        instr_timeline[instr][t] = stage

# Print a timeline table
print("Instruction".ljust(15), end="")
for t in times:
    print(f"{t:>8}", end="")
print()
for instr, time_dict in instr_timeline.items():
    print(instr.ljust(15), end="")
    for t in times:
        stage = time_dict.get(t, "")
        print(stage.replace("pc_", "").center(8), end="")
    print()
