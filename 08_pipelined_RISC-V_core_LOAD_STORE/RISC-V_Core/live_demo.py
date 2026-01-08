import socket
import json
import time

def start_client():
    host = 'localhost'
    port = 8888

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
    f = s.makefile('r', encoding='utf-8')

    try:
        while True:
            line = f.readline()
            if not line: break

            try:
                st = json.loads(line.strip())

                # --- PRETTY PRINT PIPELINE ---
                print("\n" + "="*40)
                print(f" ⏱️  CYCLE: {st.get('cycle', 0)}")
                print("-" * 40)

                # Helper to format hex
                def h(val): return f"0x{int(val):08x}" if val is not None else "   -   "

                print(f" [IF]  PC: {h(st.get('STAGE_IF'))} | Instr: {h(st.get('INSTR_IF'))}")
                print(f" [ID]  PC: {h(st.get('STAGE_ID'))} | Instr: {h(st.get('INSTR_ID'))}")
                print(f" [EX]  PC: {h(st.get('STAGE_EX'))} | ALU Result: {h(st.get('EX_ALU_RESULT'))}")
                print(f" [MEM] PC: {h(st.get('STAGE_MEM'))}")
                print(f" [WB]  PC: {h(st.get('STAGE_WB'))} | Writeback: {h(st.get('WB_DATA'))}")
                print("="*40)

            except json.JSONDecodeError:
                print(f"⚠️ Bad Data: {line}")

            cmd = input("👉 [Enter] Step | [q] Quit: ")
            if cmd == 'q':
                s.sendall(b"quit\n")
                break
            else:
                s.sendall(b"step\n")

    except KeyboardInterrupt:
        s.sendall(b"quit\n")
    finally:
        f.close()
        s.close()

if __name__ == "__main__":
    start_client()