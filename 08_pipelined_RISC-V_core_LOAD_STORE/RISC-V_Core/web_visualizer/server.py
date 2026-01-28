import socketio
import copy
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from .bridge import ChiselBridge
from live_debug.decoder import decode_rv32i

bridge = ChiselBridge()

debug_state = { "cursor": -1, "history": [] }

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
    data = copy.deepcopy(raw_data)

    # 1. Decode Instructions for ALL stages (No skipping)
    data['asm'] = {}
    data['pc_hex'] = {}
    for stage in ['if', 'id', 'ex', 'mem', 'wb']:
        val = data.get('instr', {}).get(stage, 0)
        data['asm'][stage] = decode_rv32i(val)
        pval = data.get('pc', {}).get(stage, 0)
        data['pc_hex'][stage] = f"0x{pval:08x}"

    # 2. Extract Register Indices (Directly from current instruction)
    id_instr = data.get('instr', {}).get('id', 0)
    ex_instr = data.get('instr', {}).get('ex', 0)
    data['id_info'] = extract_registers(id_instr)
    data['ex_info'] = extract_registers(ex_instr)

    # 3. Register File State (Accumulator)
    # We still need to remember the values because hardware only sends updates,
    # but we DO NOT buffer/delay the 'we' signal anymore.
    prev_regs = [0] * 32
    if debug_state["history"]:
        prev_regs = debug_state["history"][-1]["registers"][:]

    # Apply Writeback DIRECTLY from current cycle signals
    wb = data.get("wb", {})
    we = wb.get("we")
    rd = wb.get("rd")
    wdata = wb.get("wdata")

    current_regs = prev_regs[:]
    if we and rd != 0:
        current_regs[rd] = wdata

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
        if not debug_state["history"]:
            add_to_history(bridge.step(0))
    if debug_state["history"]:
        await sio.emit('update', debug_state["history"][debug_state["cursor"]])

@sio.event
async def command(sid, data):
    action = data.get('action')
    response = None

    if action == 'step':
        if debug_state["cursor"] < len(debug_state["history"]) - 1:
            debug_state["cursor"] += 1
            response = debug_state["history"][debug_state["cursor"]]
        else:
            response = add_to_history(bridge.step(1))

    elif action == 'back':
        if debug_state["cursor"] > 0:
            debug_state["cursor"] -= 1
            response = debug_state["history"][debug_state["cursor"]]

    elif action == 'reset':
        bridge.reset()
        debug_state["history"] = []
        response = add_to_history(bridge.step(0))

    elif action == 'run':
        for _ in range(5): response = add_to_history(bridge.step(1))

    if response: await sio.emit('update', response)