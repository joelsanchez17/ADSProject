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

# --- THIS IS THE MISSING LINE CAUSING YOUR ERROR ---
socket_app = socketio.ASGIApp(sio, app)
# ---------------------------------------------------

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

def safe_int(val):
    if val is None: return 0
    if isinstance(val, int): return val
    if isinstance(val, str):
        val = val.strip()
        if val.startswith("0x"): return int(val, 16)
        if val.startswith("b"): return int(val, 2)
        try: return int(val)
        except: return 0
    return 0

def process_snapshot(raw_data):
    data = copy.deepcopy(raw_data)

    # 1. Decode Instructions (Just for text display)
    data['asm'] = {}
    data['pc_hex'] = {}
    for stage in ['if', 'id', 'ex', 'mem', 'wb']:
        raw_instr = data.get('instr', {}).get(stage, 0)
        data['asm'][stage] = decode_rv32i(safe_int(raw_instr))
        data['pc_hex'][stage] = f"0x{safe_int(data.get('pc', {}).get(stage, 0)):08x}"

    # 2. Hardware Register File (Direct from JSON)
    reg_map = data.get("regs", {})
    regs_list = [0] * 32
    if isinstance(reg_map, dict):
        for k, v in reg_map.items():
            idx = int(k.replace("x", ""))
            if 0 <= idx < 32:
                regs_list[idx] = safe_int(v)

    # 3. EX Stage: Use Hardware Signals
    if 'ex' not in data: data['ex'] = {}
    data['ex']['val_a'] = safe_int(data['ex'].get('alu_op_a', 0))
    data['ex']['val_b'] = safe_int(data['ex'].get('alu_op_b', 0))

    # 4. ID Info
    id_instr = data.get('instr', {}).get('id', 0)
    data['id_info'] = extract_registers(id_instr)

    return {
        "raw": raw_data,
        "enriched": data,
        "registers": regs_list
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