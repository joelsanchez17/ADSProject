import socketio
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from .bridge import ChiselBridge

# 1. Initialize Chisel Bridge
bridge = ChiselBridge()

# 2. Setup Web Server
sio = socketio.AsyncServer(async_mode='asgi', cors_allowed_origins='*')
app = FastAPI()
app.mount("/static", StaticFiles(directory="web_visualizer/static"), name="static")
socket_app = socketio.ASGIApp(sio, app)

# 3. Serve the HTML Page
@app.get("/")
async def read_index():
    return FileResponse('web_visualizer/templates/index.html')

# 4. WebSocket Event Handlers
@sio.event
async def connect(sid, environ):
    print(f"👤 Browser Connected: {sid}")
    # Initialize connection to hardware if needed
    if not bridge.sock:
        bridge.connect()
        # Get initial state
        bridge.step(0) # Just to populate history if empty, or use bridge.receive_snapshot()

    # Send current state to new browser tab
    await sio.emit('update', bridge.get_latest(), to=sid)

@sio.event
async def command(sid, data):
    """Handle buttons clicked in the browser."""
    action = data.get('action')

    response = {}

    if action == 'step':
        response = bridge.step(1)
    elif action == 'reset':
        response = bridge.reset()
    elif action == 'run':
        # Run 5 steps for demo
        response = bridge.step(5)

    # Broadcast new state to ALL connected browsers
    await sio.emit('update', response)