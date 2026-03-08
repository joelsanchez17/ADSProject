import uvicorn
import argparse

if __name__ == "__main__":
    #  no longer start Chisel, The server boots instantly.
    print("🚀 Starting Web Visualizer at http://localhost:8080")
    uvicorn.run("web_visualizer.server:socket_app", host="0.0.0.0", port=8080, reload=True)