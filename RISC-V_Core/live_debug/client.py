import socket
import json
import time
import sys
import tty
import termios
import os
from dataclasses import dataclass
from typing import Optional, Dict, Any, List
from .ui import show_snapshot, Colors

@dataclass
class Breakpoint:
    kind: str
    value: int

class LiveClient:
    def __init__(self, host: str, port: int):
        self.host, self.port = host, port
        self.sock, self.f = None, None
        self.history, self.view_idx = [], -1
        self.record_file = None
        self.bp = None

    def get_key(self):
        fd = sys.stdin.fileno()
        old = termios.tcgetattr(fd)
        try:
            tty.setraw(fd)
            ch = sys.stdin.read(1)
            if ch == '\x1b':
                seq = sys.stdin.read(2)
                if seq == '[A': return 'UP'
                if seq == '[B': return 'DOWN'
                if seq == '[C': return 'RIGHT'
                if seq == '[D': return 'LEFT'
                if seq == '[5': sys.stdin.read(1); return 'PGUP'
                if seq == '[6': sys.stdin.read(1); return 'PGDN'
                return 'ESC'
            return ch
        finally:
            termios.tcsetattr(fd, termios.TCSADRAIN, old)

    def connect(self):
        print("\033[H\033[J")
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        while True:
            try:
                s.connect((self.host, self.port))
                break
            except: time.sleep(0.5)
        self.sock = s
        self.f = s.makefile("r", encoding="utf-8")

    def close(self):
        try:
            if self.record_file: self.record_file.close()
            if self.f: self.f.close()
            if self.sock: self.sock.close()
        except: pass

    def send(self, msg): self.sock.sendall((msg.strip()+"\n").encode())

    def recv_snapshot(self):
        while True:
            line = self.f.readline()
            if not line: raise EOFError
            try:
                s = json.loads(line)
                self.history.append(s)
                self.view_idx = len(self.history)-1
                if self.record_file: self.record_file.write(json.dumps(s)+"\n")
                return s
            except: continue

    def step_delta(self, n):
        target = self.view_idx + n
        live_head = len(self.history) - 1
        if target <= live_head:
            self.view_idx = max(0, target)
            return
        needed = target - live_head
        self.view_idx = live_head
        for _ in range(needed):
            self.send("step")
            self.recv_snapshot()

    def handle_command_input(self):
        print("\033[?25h")
        os.system("stty sane")
        try:
            cmd = input(f"\n{Colors.BOLD}COMMAND > {Colors.RESET}").strip().lower()
            if cmd.startswith("record on "):
                path = cmd.split(" ", 2)[2]
                self.record_file = open(path, "w")
                print(f"Recording to {path}")
            elif cmd == "record off":
                if self.record_file: self.record_file.close()
                self.record_file = None
                print("Recording stopped")
            elif cmd in ["reset", "clear"]:
                self.send("reset")
                self.history.clear()
                self.recv_snapshot()
            elif cmd.startswith("bp "):
                parts = cmd.split()
                if len(parts) > 1:
                    val = int(parts[-1], 16) if parts[-1].startswith("0x") else int(parts[-1])
                    kind = parts[1] if len(parts) > 2 else "if_pc"
                    self.bp = Breakpoint(kind, val)
                    print(f"BP set: {kind}={val:#x}")
            elif cmd in ["help", "h"]:
                self.show_help()
            if cmd: time.sleep(0.8)
        except Exception as e: print(f"Error: {e}"); time.sleep(1)

    def show_help(self):
        print(f"\n{Colors.BOLD}--- HELP ---{Colors.RESET}")
        print(" [Arrows]     Step Back/Forward (1 cycle)")
        print(" [PgUp/Dn]    Jump Back/Forward (100 cycles)")
        print(" [Up/Down]    Jump Back/Forward (10 cycles)")
        print(" [Home]       Go to Start")
        print(" [Enter]      Jump to Live Head")
        print(" [:]          Enter Command Mode")
        print(" [q]          Quit")
        print("\nCommands (type ':' first):")
        print("  bp <val>        Set Breakpoint (IF PC)")
        print("  record on <f>   Start recording to file")
        print("  reset / clear   Reset Processor & History")
        input("\nPress Enter to return...")

    def run(self):
        self.connect()
        try:
            self.recv_snapshot()
            while True:
                print("\033[H", end="")
                self.view_idx = max(0, min(self.view_idx, len(self.history)-1))
                show_snapshot(self.history[self.view_idx])

                live = len(self.history)-1
                is_live = (self.view_idx == live)
                print("\033[K")
                if is_live: print(f"{Colors.GREEN} 🟢 LIVE HEAD {Colors.RESET} | Cycle: {self.history[-1].get('cycle')}\033[K")
                else:       print(f"{Colors.ORANGE} ⏪ HISTORY {Colors.RESET} | View: {self.view_idx}/{live}\033[K")
                print(f"{Colors.DIM} [⬆️/⬇️  +/-10] [➡️ /⬅️  +/-1] [Enter=Live] [:=Cmd] [h=Help] [q=Quit]{Colors.RESET}\033[K")

                key = self.get_key()
                if key=='q': break
                elif key=='\r' or key=='\n': self.view_idx = live
                elif key==':' or key == ';': self.handle_command_input()
                elif key=='h' or key == '?': self.handle_command_input()
                elif key=='RIGHT': self.step_delta(1)
                elif key=='LEFT':  self.step_delta(-1)
                elif key=='UP':    self.step_delta(10)
                elif key=='DOWN':  self.step_delta(-10)
                elif key=='PGUP':  self.step_delta(100)
                elif key=='PGDN':  self.step_delta(-100)
                elif key=='HOME': self.view_idx = 0
        finally:
            self.close()