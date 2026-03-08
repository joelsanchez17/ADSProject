import socketio
import copy
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from .bridge import ChiselBridge
from live_debug.decoder import decode_rv32i
import os
import shutil
import uuid
import subprocess
from pydantic import BaseModel
from typing import Dict
from pydantic import BaseModel
import socket


active_bridges = {}  # Stores bridges mapped by Session ID

debug_state = { "cursor": -1, "history": [] }

sio = socketio.AsyncServer(async_mode='asgi', cors_allowed_origins='*')
app = FastAPI()


app.mount("/static", StaticFiles(directory="web_visualizer/static"), name="static")


active_sessions = {}  # Maps session_id -> {"bridge": obj, "history": [], "cursor": 0}

socket_app = socketio.ASGIApp(sio, app)

def find_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('', 0))
        return s.getsockname()[1]

@app.get("/")
async def read_index():
    return FileResponse('web_visualizer/templates/index.html')

class CompileRequest(BaseModel):
    scala_files: Dict[str, str]
    asm_code: str

@app.post("/compile")
async def compile_code(req: CompileRequest):
    session_id = f"sess_{uuid.uuid4().hex[:8]}"
    base_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(base_dir, ".."))
    template_dir = os.path.join(project_root, "infrastructure_template")
    session_dir = os.path.join(project_root, "temp_sessions", session_id)

    try:
        shutil.copytree(template_dir, session_dir)
        scala_dir = os.path.join(session_dir, "src", "main", "scala")

        os.makedirs(scala_dir, exist_ok=True)

        for filename, content in req.scala_files.items():
            with open(os.path.join(scala_dir, filename), "w") as f:
                f.write(content)

        with open(os.path.join(session_dir, "test_prog.s"), "w") as f:
            f.write(req.asm_code)

        student_port = find_free_port()
        env = os.environ.copy()
        env["CHISEL_PORT"] = str(student_port)

        # 1. USE POPEN TO RUN SBT IN THE BACKGROUND
        process = subprocess.Popen(
            ["sbt", "--batch", "testOnly *LivePipelineTest"],
            cwd=session_dir,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            universal_newlines=True
        )

        # 2. READ THE LOGS LIVE
        log_output = ""
        is_ready = False

        while True:
            line = process.stdout.readline()
            if not line:
                break # Process ended unexpectedly

            log_output += line
            print(line, end="", flush=True) # Print to your VM terminal so you can watch it!

            # Did compilation fail?
            if "Failed tests:" in line or "sbt.TestsFailedException" in line:
                break

            # Did compilation succeed and Chisel is waiting for us?
            if "Waiting for Python on port" in line:
                is_ready = True
                break

        clean_logs = log_output.replace(session_dir, "[WORKSPACE]")

        # 3. IF READY, CONNECT THE BRIDGE IMMEDIATELY!
        if is_ready:
            bridge = ChiselBridge(port=student_port)
            bridge.connect()
            initial_raw = bridge.receive_snapshot() # Get Cycle 0
            initial_proc = process_snapshot(initial_raw) if initial_raw else None

            active_sessions[session_id] = {
                "process": process, # Save the SBT process so we can kill it later
                "bridge": bridge,
                "history": [initial_proc] if initial_proc else [],
                "cursor": 0
            }
            return {"status": "success", "message": "Compilation successful!", "logs": clean_logs, "session_id": session_id}
        else:
            # It failed or got stuck, kill it
            process.terminate()
            return {"status": "error", "message": "Chisel Compilation Failed.", "logs": clean_logs}

    except Exception as e:
        return {"status": "error", "message": f"Server Error: {str(e)}", "logs": ""}


# --- HELPER FUNCTIONS ---
def extract_registers(instr_int):
    if not instr_int: return {"rs1":0, "rs2":0, "rd":0}
    return {"rs1": (instr_int >> 15) & 0x1F, "rs2": (instr_int >> 20) & 0x1F, "rd": (instr_int >> 7) & 0x1F}

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
    data['asm'] = {}
    data['pc_hex'] = {}
    for stage in ['if', 'id', 'ex', 'mem', 'wb']:
        raw_instr = data.get('instr', {}).get(stage, 0)
        data['asm'][stage] = decode_rv32i(safe_int(raw_instr))
        data['pc_hex'][stage] = f"0x{safe_int(data.get('pc', {}).get(stage, 0)):08x}"

    reg_map = data.get("regs", {})
    regs_list = [0] * 32
    if isinstance(reg_map, dict):
        for k, v in reg_map.items():
            idx = int(k.replace("x", ""))
            if 0 <= idx < 32: regs_list[idx] = safe_int(v)

    if 'ex' not in data: data['ex'] = {}
    data['ex']['val_a'] = safe_int(data['ex'].get('alu_op_a', 0))
    data['ex']['val_b'] = safe_int(data['ex'].get('alu_op_b', 0))
    data['id_info'] = extract_registers(data.get('instr', {}).get('id', 0))
    return {"raw": raw_data, "enriched": data, "registers": regs_list}

def add_to_history(session_id, raw_snap):
    if not raw_snap: return
    processed = process_snapshot(raw_snap)
    sess = active_sessions[session_id]
    sess["history"].append(processed)
    sess["cursor"] = len(sess["history"]) - 1
    return processed

# --- MULTI-USER SOCKET.IO EVENTS ---
@sio.event
async def connect(sid, environ):
    pass # Do nothing! We wait until they compile to start hardware.

@sio.event
async def command(sid, data):
    session_id = data.get('session_id')
    if not session_id or session_id not in active_sessions:
        return # Ignore commands from unconnected users

    sess = active_sessions[session_id]
    bridge = sess["bridge"]
    action = data.get('action')
    val = int(data.get('value', 1))
    response = None

    if action == 'init': # Get cycle 0 right after compilation
        if sess["history"]: response = sess["history"][0]

    elif action == 'step':
        target = sess["cursor"] + 1
        if target < len(sess["history"]):
            sess["cursor"] = target
            response = sess["history"][sess["cursor"]]
        else:
            response = add_to_history(session_id, bridge.step(1))

    elif action == 'run':
        for _ in range(val):
            if sess["cursor"] < len(sess["history"]) - 1:
                sess["cursor"] += 1
                response = sess["history"][sess["cursor"]]
            else:
                response = add_to_history(session_id, bridge.step(1))

    elif action == 'back':
        sess["cursor"] = max(0, sess["cursor"] - val)
        response = sess["history"][sess["cursor"]]

    elif action == 'reset':
        bridge.reset()
        sess["history"] = []
        response = add_to_history(session_id, bridge.step(0))

    if response:
        await sio.emit('update', response)