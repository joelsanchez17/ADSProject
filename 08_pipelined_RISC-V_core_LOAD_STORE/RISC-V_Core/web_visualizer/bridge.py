import socket
import json
import time

class ChiselBridge:
    def __init__(self, host="localhost", port=8888):
        self.host = host
        self.port = port
        self.sock = None
        self.f = None
        self.history = []

    def connect(self):
        """Connect to the Chisel TCP server."""
        print(f"🔌 Connecting to Chisel at {self.host}:{self.port}...")
        while True:
            try:
                self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.sock.connect((self.host, self.port))
                self.f = self.sock.makefile("r", encoding="utf-8")
                print("✅ Connected to Hardware!")
                return
            except ConnectionRefusedError:
                print("   Waiting for simulation...")
                time.sleep(1)

    def send_command(self, cmd: str):
        """Send raw command to Chisel."""
        if not self.sock: self.connect()
        self.sock.sendall((cmd.strip() + "\n").encode())

    def receive_snapshot(self):
        """Read one JSON line from Chisel."""
        if not self.f: return None
        try:
            line = self.f.readline()
            if not line: return None
            data = json.loads(line)
            self.history.append(data) # Keep a history buffer
            return data
        except json.JSONDecodeError:
            return None

    def step(self, steps=1):
        """Advance N cycles."""
        for _ in range(steps):
            self.send_command("step")
            self.receive_snapshot()
        return self.get_latest()

    def get_latest(self):
        """Return the most recent snapshot."""
        if not self.history: return {}
        return self.history[-1]

    def reset(self):
        self.send_command("reset")
        self.history = []
        self.receive_snapshot() # Get cycle 0
        return self.get_latest()