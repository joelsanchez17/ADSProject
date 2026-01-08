import socket
import json
import time

def start_client():
    host = 'localhost'
    port = 8888  # Must match Scala

    print(f"🔄 [PYTHON] Connecting to {host}:{port}...")

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    while True:
        try:
            s.connect((host, port))
            break
        except ConnectionRefusedError:
            print("   ... Waiting for Chisel ...")
            time.sleep(2)

    print("✅ [PYTHON] Connected!")

    # CREATE A FILE-LIKE INTERFACE
    # This ensures we read exactly one line at a time (syncs with Scala println)
    f = s.makefile('r', encoding='utf-8')

    try:
        while True:
            # 1. Read Line (Blocking)
            line = f.readline()

            # If line is empty, connection is dead
            if not line:
                print("⚠️  Chisel disconnected (EOF).")
                break

            # 2. Parse Data
            try:
                state = json.loads(line.strip())
                cycle = state.get('cycle', '?')
                pc = state.get('pc', '?')
                print(f"   ⏱️  Cycle: {cycle} | 📍 Result/PC: {pc}")
            except json.JSONDecodeError:
                print(f"   ⚠️  Bad Data: {line}")

            # 3. User Input
            cmd = input("   👉 Press [Enter] to Step... ")

            if cmd == "quit":
                s.sendall(b"quit\n")
                break
            else:
                s.sendall(b"step\n")

    except KeyboardInterrupt:
        print("\n[PYTHON] Exiting.")
        s.sendall(b"quit\n")
    except BrokenPipeError:
        print("❌ Socket closed.")
    finally:
        f.close()
        s.close()

if __name__ == "__main__":
    start_client()