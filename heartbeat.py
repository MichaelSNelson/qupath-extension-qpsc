import socket
import time
import argparse

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", type=str, required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.connect((args.host, args.port))
        print("Python: Connected to QuPath workflow, sending heartbeats.")
        try:
            while True:
                s.sendall(b"heartbeat\n")
                time.sleep(2)
        except (BrokenPipeError, ConnectionResetError):
            print("Python: QuPath disconnected or closed. Exiting.")
        except KeyboardInterrupt:
            print("Python: Interrupted, exiting.")

if __name__ == "__main__":
    main()
