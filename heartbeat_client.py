import socket
import argparse
import time
import sys

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", type=str, required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    host,port = args.host, args.port
    #host ="127.0.0.1" 
    #port = 53717
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.connect((host,port))
        print("Python: Connected to QuPath workflow, sending heartbeats.")
        while True:
            try:
                s.sendall(b"heartbeat\n")
                time.sleep(2)
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
                print("Python: Lost connection to QuPath workflow, exiting.")
                break
    except Exception as ex:
        print(f"Python: Could not connect to QuPath: {ex}")
        sys.exit(1)
    finally:
        s.close()

if __name__ == "__main__":
    main()
