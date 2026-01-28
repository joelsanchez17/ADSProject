import socketio
import copy
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from .bridge import ChiselBridge
from live_debug.decoder import decode_rv32i

bridge = ChiselBridge()

# Global Debug State
debug_state = {
    "cursor": -1,
    "history": [] # Each item will have: { raw_data, enriched_data, registers }
}

# Initial Registers (All Zero)
initial_regs = [0] * 32

sio = socketio.AsyncServer(async_mode='asgi', cors_allowed_origins='*')
app = FastAPI()
app.mount("/static", StaticFiles(directory="web_visualizer/static"), name="static")
socket_app = socketio.ASGIApp(sio, app)

@app.get("/")
async def read_index():
    return FileResponse('web_visualizer/templates/index.html')

def extract_registers(instr_int):
    if not instr_int: return {"rs1":0, "rs2":0, "rd":0}
    return {
        "rs1": (instr_int >> 15) & 0x1F,
        "rs2": (instr_int >> 20) & 0x1F,
        "rd":  (instr_int >> 7)  & 0x1F
    }

def process_snapshot(raw_data):
    """
    1. Enriches data (ASM, Hex)
    2. Calculates Register File state based on history
    """
    data = copy.deepcopy(raw_data)

    # --- 1. Basic Enrichment ---
    data['asm'] = {}
    data['pc_hex'] = {}
    for stage in ['if', 'id', 'ex', 'mem', 'wb']:
        val = data.get('instr', {}).get(stage, 0)
        data['asm'][stage] = decode_rv32i(val)
        pval = data.get('pc', {}).get(stage, 0)
        data['pc_hex'][stage] = f"0x{pval:08x}"

    # Extract Register Indices
    id_instr = data.get('instr', {}).get('id', 0)
    data['id_info'] = extract_registers(id_instr)

    # --- 2. Shadow Register File Logic ---
    # Get previous registers
    if debug_state["history"]:
        prev_regs = debug_state["history"][-1]["registers"][:]
    else:
        prev_regs = [0] * 32

    # Apply Writeback
    wb = data.get("wb", {})
    we = wb.get("we")
    rd = wb.get("rd")
    wdata = wb.get("wdata")

    # Create new register state
    current_regs = prev_regs[:]
    if we and rd != 0:
        current_regs[rd] = wdata

    # Attach to snapshot
    return {
        "raw": raw_data,
        "enriched": data,
        "registers": current_regs
    }

def add_to_history(raw_snap):
    if not raw_snap: return
    processed = process_snapshot(raw_snap)
    debug_state["history"].append(processed)
    debug_state["cursor"] = len(debug_state["history"]) - 1
    return processed

@sio.event
async def connect(sid, environ):
    if not bridge.sock:
        bridge.connect()
        # Initial Reset/Step to sync
        if not debug_state["history"]:
            raw = bridge.step(0)
            add_to_history(raw)

    # Send current cursor
    if debug_state["history"]:
        curr = debug_state["history"][debug_state["cursor"]]
        await sio.emit('update', curr)

@sio.event
async def command(sid, data):
    action = data.get('action')
    response = None

    if action == 'step':
        # If looking at history, just move forward
        if debug_state["cursor"] < len(debug_state["history"]) - 1:
            debug_state["cursor"] += 1
            response = debug_state["history"][debug_state["cursor"]]
        else:
            # Step hardware
            raw = bridge.step(1)
            response = add_to_history(raw)

    elif action == 'back':
        if debug_state["cursor"] > 0:
            debug_state["cursor"] -= 1
            response = debug_state["history"][debug_state["cursor"]]

    elif action == 'run':
        # Simple run: 5 steps
        for _ in range(5):
            raw = bridge.step(1)
            response = add_to_history(raw)

    elif action == 'reset':
        bridge.reset()
        debug_state["history"] = []
        raw = bridge.step(0)
        response = add_to_history(raw)

    if response:
        await sio.emit('update', response)