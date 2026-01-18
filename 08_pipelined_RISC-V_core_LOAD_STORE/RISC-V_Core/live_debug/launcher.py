import subprocess
import os
import signal
import atexit
import socket
import time

SERVER_PROCESS = None

def cleanup():
    """Restores terminal and kills server."""
    global SERVER_PROCESS
    print("\033[?25h") # Show Cursor
    os.system("stty sane")
    if SERVER_PROCESS:
        try:
            os.killpg(os.getpgid(SERVER_PROCESS.pid), signal.SIGTERM)
        except Exception: pass
        SERVER_PROCESS = None

def ensure_server_running(host, port):
    global SERVER_PROCESS
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        if s.connect_ex((host, port)) == 0: return

    print(f"🚀 Launching Chisel Simulation on port {port}...")
    print("   (This takes 10-20 seconds to compile...)")
    log_file = open("chisel_server.log", "w")
    cmd = ["sbt", "testOnly PipelinedRV32I_Tester.LivePipelineTest"]

    SERVER_PROCESS = subprocess.Popen(
        cmd,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        preexec_fn=os.setsid
    )
    atexit.register(cleanup)
    print("⏳ Waiting 15 seconds for warm up...")
    time.sleep(15)