import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rappresenta un nodo in un sistema distribuito che comunica via Multicast.
 * Si connette a un server via TCP per la sincronizzazione (START/SHUTDOWN).
 * * @author Yammi2002
 * @version 1.0
 */
public class Node {
    /**
     * Punto di ingresso principale per il Nodo.
     * @param args da riga di comando: ID del nodo e numero totale di nodi.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java Node <ID> <NUM_NODI>");
            return;
        }

        int mioID = Integer.parseInt(args[0]);
        int numNodiTotal = Integer.parseInt(args[1]);
        float LP = 0.2f; 
        
        // Mappa thread-safe per memorizzare i messaggi inviati (per recupero perdite)
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

                tSender.join();
                System.out.println("Nodo " + mioID + ": Invio completato. Verifico ricezione dagli altri...");

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

/**
 * Gestisce l'invio di messaggi multicast con una probabilità di perdita simulata.
 */
class Sender implements Runnable {
    private int mioID;
    private MulticastSocket socket;
    private InetAddress group;
    private float lp;
    private Map<Integer, String> history;

    /**
     * Costruttore del modulo Sender.
     * @param id Identificativo univoco del nodo.
     * @param s Socket multicast esistente.
     * @param g Indirizzo del gruppo multicast.
     * @param lp Loss Probability (probabilità di perdita pacchetti).
     * @param h Mappa della cronologia messaggi per gestire richieste di rispedizione.
     */
    Sender(int id, MulticastSocket s, InetAddress g, float lp, Map<Integer, String> h) {
        this.mioID = id; this.socket = s; this.group = g; this.lp = lp; this.history = h;
    }

    /**
     * Ciclo principale: invia 100 messaggi e gestisce la probabilità di perdita.
     */
    public void run() {
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

        // Invia pacchetto di chiusura per segnalare la fine delle trasmissioni
        for (int j = 0; j < 5; j++) {
            invia("DATA:" + mioID + ":101");
            try { Thread.sleep(200); } catch (InterruptedException e) {}
        }
    }

    /**
     * Invia un pacchetto datagramma sul gruppo multicast.
     * @param testo Il contenuto testuale del messaggio.
     */
    private void invia(String testo) {
        try {
            byte[] buf = testo.getBytes();
            socket.send(new DatagramPacket(buf, buf.length, group, 4446));
        } catch (IOException e) {}
    }
}

/**
 * Gestisce la ricezione dei messaggi multicast e il recupero dei pacchetti persi.
 */
class Receiver implements Runnable {
    private int mioID;
    private MulticastSocket socket;
    private InetAddress group;
    private int[] nextExpected;
    private Map<Integer, String> history;

    /**
     * Costruttore del modulo Receiver.
     * @param id Identificativo del nodo.
     * @param s Socket multicast.
     * @param g Indirizzo del gruppo.
     * @param total Numero totale di nodi nella rete.
     * @param h Cronologia locale per rispondere a richieste LOST di altri nodi.
     */
    Receiver(int id, MulticastSocket s, InetAddress g, int total, Map<Integer, String> h) {
        this.mioID = id; this.socket = s; this.group = g; this.history = h;
        this.nextExpected = new int[total + 1];
        Arrays.fill(nextExpected, 1);
    }

    /**
     * Verifica se sono stati ricevuti tutti i messaggi attesi da tutti i nodi.
     * @return true se non mancano messaggi, false altrimenti.
     */
    public boolean tuttoRicevuto() {
        for (int i = 1; i < nextExpected.length; i++) {
            if (i != mioID && nextExpected[i] <= 100) return false;
        }
        return true;
    }

    /**
     * Ascolta continuamente sul socket multicast e gestisce protocolli DATA e LOST.
     */
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
                        // Rilevato un salto: richiede i messaggi mancanti
                        for (int m = nextExpected[idMitt]; m < idMsg; m++) {
                            if (m <= 100) {
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
                    // Se il messaggio perso è mio, lo rispedisco
                    if (idSmarrito == mioID && history.containsKey(msgSmarrito)) {
                        invia(history.get(msgSmarrito));
                    }
                }
            } catch (IOException e) { break; }
        }
    }

    /**
     * Invia un pacchetto di risposta o richiesta sul gruppo multicast.
     * @param testo Messaggio da inviare.
     */
    private void invia(String testo) {
        try {
            byte[] b = testo.getBytes();
            socket.send(new DatagramPacket(b, b.length, group, 4446));
        } catch (IOException e) {}
    }
}