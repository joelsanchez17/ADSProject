
import argparse
from .launcher import ensure_server_running
from .client import LiveClient

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", default=8888, type=int)
    args = ap.parse_args()

    ensure_server_running(args.host, args.port)
    LiveClient(args.host, args.port).run()

if __name__ == "__main__":
    main()