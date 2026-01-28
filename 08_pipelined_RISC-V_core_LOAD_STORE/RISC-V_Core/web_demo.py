import uvicorn
import argparse
from live_debug.launcher import ensure_server_running # Reuse your existing launcher!

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8888)
    args = parser.parse_args()

    # 1. Start Chisel (reuses your existing logic)
    ensure_server_running("localhost", args.port)

    # 2. Start Web Server
    print("🚀 Starting Web Visualizer at http://localhost:8080")
    # CHANGE 8000 -> 8080 BELOW:
    uvicorn.run("web_visualizer.server:socket_app", host="0.0.0.0", port=8080, reload=True)