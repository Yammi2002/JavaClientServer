# JavaClientServer

A Java-based distributed system that simulates reliable message exchange over a multicast channel with a simulated probability of failure.
The project uses a **Server-Node architecture** where nodes communicate via UDP Multicast while being synchronized by a central TCP Server.

---

## Features
* **TCP Synchronization**: Nodes wait for a `START` signal from the Server before communicating.
* **Multicast Communication**: Efficient one-to-many message passing using `MulticastSocket`.
* **Packet Loss Simulation**: Nodes have a configurable loss probability (`LP`) to simulate real-world network instability.
* **Reliability Layer**: Implements a "Gap Detection" mechanism. If a node detects a missing sequence number, it requests the lost packet from the original sender.
* **Graceful Shutdown**: The Server coordinates a `SHUTDOWN` once all nodes confirm they have received all data.

---

##  Installation & Setup

1.  **Clone the repository**:
    ```bash
    git clone https://github.com/Yammi2002/JavaClientServer.git
    ```
2.  **Compile the source files**:
    ```bash
    javac *.java
    ```

---

## How to Run

### 1. Start the Server
The server waits for a specific number of nodes (default is **3**) to connect via TCP.
```bash
java Server
```

### 2. Start Nodes
Open a new terminal for each node. You must provide a unique ID and the total number of nodes.
# Terminal 1
```bash
java Node 1 3
```
# Terminal 2
```bash
java Node 2 3
```
# Terminal 3
```bash
java Node 3 3
```
## Documentation
This project includes full Javadoc documentation. To view it navigate to the doc/ directory and open index.html and server.html in your preferred web browser.
