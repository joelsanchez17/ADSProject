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
import socket
import asyncio
from pydantic import BaseModel
from typing import Dict, Optional
from typing import Dict


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

# New from here
class CompileRequest(BaseModel):
    scala_files: Dict[str, str]
    asm_code: str
    session_id: Optional[str] = None  # 🚨 NEW: Accept the session ID from the browser!

@app.post("/compile")
async def compile_code(req: CompileRequest):
    base_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(base_dir, ".."))
    template_dir = os.path.join(project_root, "infrastructure_template")

    # 1. CHECK FOR SESSION REUSE
    is_new_session = True
    if req.session_id and req.session_id in active_sessions:
        session_id = req.session_id
        is_new_session = False
        print(f"♻️  Reusing existing session: {session_id}")

        # KILL the old SBT process and close the old TCP socket so the port is freed!
        old_sess = active_sessions[session_id]
        if old_sess.get("process"):
            try: old_sess["process"].terminate()
            except: pass
        if old_sess.get("bridge") and old_sess["bridge"].sock:
            try: old_sess["bridge"].sock.close()
            except: pass
    else:
        session_id = f"sess_{uuid.uuid4().hex[:8]}"
        print(f"🆕 Creating new session: {session_id}")

    session_dir = os.path.join(project_root, "temp_sessions", session_id)
    scala_dir = os.path.join(session_dir, "src", "main", "scala", "core_tile")

    try:
        # 2. ONLY COPY THE TEMPLATE IF IT IS A BRAND NEW SESSION
        if is_new_session:
            shutil.copytree(template_dir, session_dir)
            os.makedirs(scala_dir, exist_ok=True)

        # 3. OVERWRITE THE FILES WITH THE NEW CODE
        for filename, content in req.scala_files.items():
            with open(os.path.join(scala_dir, filename), "w") as f:
                f.write(content)

        with open(os.path.join(session_dir, "test_prog.s"), "w") as f:
            f.write(req.asm_code)

        student_port = find_free_port()
        env = os.environ.copy()
        env["CHISEL_PORT"] = str(student_port)



        # 1. RUN SBT ASYNCHRONOUSLY
        process = await asyncio.create_subprocess_exec(
            "sbt", "--batch", "testOnly *LivePipelineTest",
            cwd=session_dir, env=env, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT
        )

        log_output = ""
        compilation_failed = False
        chisel_ready = asyncio.Event()

        # 2. BACKGROUND TASK TO STREAM LOGS LIVE
        async def read_logs():
            nonlocal compilation_failed, log_output
            while True:
                line_bytes = await process.stdout.readline()
                if not line_bytes: break

                line = line_bytes.decode('utf-8', errors='replace')
                log_output += line

                # Print to local terminal AND send to web socket instantly!
                print(line, end="", flush=True)
                # Stream directly to the specific user's room!
                asyncio.create_task(sio.emit(
                    'build_log',
                    {'line': line.replace(session_dir, "[WORKSPACE]")},
                    room=session_id  # 🚨 NEW: Target the specific room
                ))

                if "Failed tests:" in line or "Compilation failed" in line:
                    compilation_failed = True
                    chisel_ready.set()
                if "Waiting for Python on port" in line:
                    chisel_ready.set()

        asyncio.create_task(read_logs())
        await chisel_ready.wait() # Wait for Chisel to open the port

        if compilation_failed:
            process.terminate()
            return {"status": "error", "message": "Chisel Compilation Failed.", "logs": log_output.replace(session_dir, "[WORKSPACE]")}

        # 3. CONNECT TO HARDWARE & FORCE CYCLE 0
        bridge = ChiselBridge(port=student_port)
        bridge.connect()
        bridge.reset() # <--- THIS FIXES THE BLANK UI! FORCES CYCLE 0 GENERATION

        initial_raw = bridge.get_latest()
        initial_proc = process_snapshot(initial_raw) if initial_raw else None

        active_sessions[session_id] = {
            "process": process,
            "bridge": bridge,
            "history": [initial_proc] if initial_proc else [],
            "cursor": 0
        }

        return {
            "status": "success",
            "message": "Simulation Started!",
            "session_id": session_id,
            "logs": log_output.replace(session_dir, "[WORKSPACE]") # Send full log backup
        }

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
# --- MULTI-USER SOCKET.IO EVENTS (WITH AGGRESSIVE DEBUGGING) ---

@sio.event
async def connect(sid, environ):
    print(f"🟢 [SOCKET.IO] New browser connected with ID: {sid}")

@sio.event
async def join_session(sid, session_id):
    sio.enter_room(sid, session_id)
    await sio.enter_room(sid, session_id)
    print(f"🚪 [ROOM] Browser {sid} explicitly joined private room: {session_id}")

@sio.event
async def command(sid, data):
    print(f"\n⚡ [COMMAND] Received from browser: {data}")

    session_id = data.get('session_id')
    if not session_id or session_id not in active_sessions:
        print(f"❌ [COMMAND] ERROR: Session {session_id} not found in active_sessions!")
        return

  
    await sio.enter_room(sid, session_id)

    sess = active_sessions[session_id]
    bridge = sess["bridge"]
    action = data.get('action')
    val = int(data.get('value', 1))
    response = None

    print(f"🔍 [COMMAND] Executing '{action}'. Current history length: {len(sess['history'])}")

    if action == 'init':
        if sess["history"]:
            response = sess["history"][0]
            print(f"✅ [COMMAND] Init successful. Grabbed Cycle 0 data.")
        else:
            print(f"❌ [COMMAND] ERROR: History is empty! Cycle 0 was never generated.")

    elif action == 'step':
        target = sess["cursor"] + 1
        if target < len(sess["history"]):
            sess["cursor"] = target
            response = sess["history"][sess["cursor"]]
            print(f"⏪ [COMMAND] Stepped using history to cycle {sess['cursor']}")
        else:
            print(f"⏩ [COMMAND] Advancing hardware 1 cycle...")
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
        print(f"⏪ [COMMAND] Went back to cycle {sess['cursor']}")

    elif action == 'reset':
        print(f"🔄 [COMMAND] Resetting hardware...")
        bridge.reset()
        sess["history"] = []
        response = add_to_history(session_id, bridge.step(0))

    if response:
        # Check exactly what instruction we are sending
        pc_check = response.get('enriched', {}).get('pc_hex', {}).get('if', 'Unknown')
        print(f"📤 [EMIT] Packaging data & sending to room '{session_id}'. (IF PC: {pc_check})")
        await sio.emit('update', response, room=session_id)
    else:
        print(f"⚠️ [EMIT] Response was None. Nothing sent to browser.")