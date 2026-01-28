import socketio
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from .bridge import ChiselBridge
from live_debug.decoder import decode_rv32i  # <--- REUSE YOUR EXISTING DECODER

bridge = ChiselBridge()

sio = socketio.AsyncServer(async_mode='asgi', cors_allowed_origins='*')
app = FastAPI()
app.mount("/static", StaticFiles(directory="web_visualizer/static"), name="static")
socket_app = socketio.ASGIApp(sio, app)

@app.get("/")
async def read_index():
    return FileResponse('web_visualizer/templates/index.html')

def enrich_data(data):
    """
    Takes raw numbers from Chisel and adds readable strings (ASM, Hex)
    so the Javascript doesn't have to do hard math.
    """
    if not data: return {}

    # 1. Decode Instructions for every stage
    # structure is data['instr']['if'], etc.
    instrs = data.get('instr', {})
    data['asm'] = {}
    for stage in ['if', 'id', 'ex', 'mem', 'wb']:
        val = instrs.get(stage, 0)
        data['asm'][stage] = decode_rv32i(val)

    # 2. Format PC as Hex strings
    pcs = data.get('pc', {})
    data['pc_hex'] = {}
    for stage in ['if', 'id', 'ex', 'mem', 'wb']:
        val = pcs.get(stage, 0)
        data['pc_hex'][stage] = f"0x{val:08x}"

    return data

@sio.event
async def connect(sid, environ):
    if not bridge.sock:
        bridge.connect()
        bridge.step(0)
    # Send enriched data
    raw = bridge.get_latest()
    await sio.emit('update', enrich_data(raw), to=sid)

@sio.event
async def command(sid, data):
    action = data.get('action')
    raw = {}

    if action == 'step':
        raw = bridge.step(1)
    elif action == 'reset':
        raw = bridge.reset()
    elif action == 'run':
        raw = bridge.step(5)

    # Send enriched data
    await sio.emit('update', enrich_data(raw))