import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Node {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java Node <ID> <NUM_NODI>");
            return;
        }

        int mioID = Integer.parseInt(args[0]);
        int numNodiTotal = Integer.parseInt(args[1]);
        float LP = 0.2f; // 20% probabilità di perdita
        
        Map<Integer, String> miaCronologia = new ConcurrentHashMap<>();

        try (Socket tcpSocket = new Socket("127.0.0.1", 5000)) {
            Scanner inTCP = new Scanner(tcpSocket.getInputStream());
            PrintWriter outTCP = new PrintWriter(tcpSocket.getOutputStream(), true);

            if (inTCP.hasNextLine() && inTCP.nextLine().equals("START")) {
                System.out.println("Nodo " + mioID + ": START ricevuto!");

                InetAddress groupAddr = InetAddress.getByName("230.0.0.1");
                int port = 4446;
                MulticastSocket mSocket = new MulticastSocket(port);
                NetworkInterface netIf = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
                mSocket.joinGroup(new InetSocketAddress(groupAddr, port), netIf);

                Receiver receiverLogic = new Receiver(mioID, mSocket, groupAddr, numNodiTotal, miaCronologia);
                Sender senderLogic = new Sender(mioID, mSocket, groupAddr, LP, miaCronologia);

                Thread tReceiver = new Thread(receiverLogic);
                Thread tSender = new Thread(senderLogic);

                tReceiver.start();
                tSender.start();

                // Aspetta che il proprio Sender finisca l'invio
                tSender.join();
                System.out.println("Nodo " + mioID + ": Invio completato. Verifico ricezione dagli altri...");

                // AFFIDABILITÀ TOTALE: Aspetta finché non ha ricevuto tutto (fino al 100) da tutti
                while (!receiverLogic.tuttoRicevuto()) {
                    Thread.sleep(500);
                }

                System.out.println("Nodo " + mioID + ": Ricezione totale confermata. Invio DONE al server.");
                outTCP.println("DONE");

                if (inTCP.hasNextLine() && inTCP.nextLine().equals("SHUTDOWN")) {
                    System.out.println("Nodo " + mioID + ": SHUTDOWN ricevuto. Chiusura.");
                    mSocket.close();
                    System.exit(0);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

// --- LOGICA DI INVIO ---
class Sender implements Runnable {
    private int mioID;
    private MulticastSocket socket;
    private InetAddress group;
    private float lp;
    private Map<Integer, String> history;

    Sender(int id, MulticastSocket s, InetAddress g, float lp, Map<Integer, String> h) {
        this.mioID = id; this.socket = s; this.group = g; this.lp = lp; this.history = h;
    }

    public void run() {
        // Invio i 100 messaggi standard
        for (int i = 1; i <= 100; i++) {
            String msg = "DATA:" + mioID + ":" + i;
            history.put(i, msg);

            if (Math.random() > lp) {
                invia(msg);
            } else {
                System.out.println("MIO MSG " + i + " PERSO (LP)");
            }
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }

        // Segnale di chiusura (ID 101) per aiutare gli altri a rilevare gap finali
        for (int j = 0; j < 5; j++) {
            invia("DATA:" + mioID + ":101");
            try { Thread.sleep(200); } catch (InterruptedException e) {}
        }
    }

    private void invia(String testo) {
        try {
            byte[] buf = testo.getBytes();
            socket.send(new DatagramPacket(buf, buf.length, group, 4446));
        } catch (IOException e) {}
    }
}

// --- LOGICA DI RICEZIONE E RECUPERO ---
class Receiver implements Runnable {
    private int mioID;
    private MulticastSocket socket;
    private InetAddress group;
    private int[] nextExpected;
    private Map<Integer, String> history;

    Receiver(int id, MulticastSocket s, InetAddress g, int total, Map<Integer, String> h) {
        this.mioID = id; this.socket = s; this.group = g; this.history = h;
        this.nextExpected = new int[total + 1];
        Arrays.fill(nextExpected, 1);
    }

    // Metodo usato dal Main per sapere quando interrompere l'attesa
    public boolean tuttoRicevuto() {
        for (int i = 1; i < nextExpected.length; i++) {
            if (i != mioID && nextExpected[i] <= 100) return false;
        }
        return true;
    }

    public void run() {
        byte[] buf = new byte[1024];
        while (true) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String msg = new String(p.getData(), 0, p.getLength());
                String[] parti = msg.split(":");

                if (parti[0].equals("DATA")) {
                    int idMitt = Integer.parseInt(parti[1]);
                    int idMsg = Integer.parseInt(parti[2]);
                    if (idMitt == mioID) continue;

                    if (idMsg == nextExpected[idMitt]) {
                        nextExpected[idMitt]++;
                    } else if (idMsg > nextExpected[idMitt]) {
                        // Rilevato buco: chiedo i messaggi mancanti
                        for (int m = nextExpected[idMitt]; m < idMsg; m++) {
                            if (m <= 100) { // Chiedo solo se è un messaggio reale
                                System.out.println("GAP! Chiedo a Nodo " + idMitt + " msg " + m);
                                invia("LOST:" + idMitt + ":" + m);
                            }
                        }
                        nextExpected[idMitt] = idMsg + 1;
                    }
                } 
                else if (parti[0].equals("LOST")) {
                    int idSmarrito = Integer.parseInt(parti[1]);
                    int msgSmarrito = Integer.parseInt(parti[2]);
                    if (idSmarrito == mioID && history.containsKey(msgSmarrito)) {
                        invia(history.get(msgSmarrito));
                    }
                }
            } catch (IOException e) { break; }
        }
    }

    private void invia(String testo) {
        try {
            byte[] b = testo.getBytes();
            socket.send(new DatagramPacket(b, b.length, group, 4446));
        } catch (IOException e) {}
    }
}