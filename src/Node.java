import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rappresenta un nodo in un sistema distribuito che comunica via Multicast.
 * Gestisce il recupero dei pacchetti persi tramite Buffer e NACK (Selective Repeat).
 */
public class Node {
	public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java Node <ID> <NUM_NODI>");
            return;
        }

        System.setProperty("java.net.preferIPv4Stack", "true");

        int mioID = Integer.parseInt(args[0]);
        int numNodiTotal = Integer.parseInt(args[1]);
        float LP = 0.2f; 
        
        Map<Integer, String> miaCronologia = new ConcurrentHashMap<>();

        try (Socket tcpSocket = new Socket("127.0.0.1", 5000)) {
            Scanner inTCP = new Scanner(tcpSocket.getInputStream());
            PrintWriter outTCP = new PrintWriter(tcpSocket.getOutputStream(), true);

            if (inTCP.hasNextLine() && inTCP.nextLine().equals("START")) {
                System.out.println("Nodo " + mioID + ": START ricevuto!");

                InetAddress groupAddr = InetAddress.getByName("230.0.0.1");
                int port = 4446;
                MulticastSocket mSocket = new MulticastSocket(port);
                
                mSocket.setLoopbackMode(false);

                // CERCHIAMO L'INTERFACCIA LOCALE CORRETTA
                NetworkInterface netIf = NetworkInterface.getByInetAddress(InetAddress.getByName("127.0.0.1"));
                if (netIf == null) {
                    netIf = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
                }
                
                // Forza i pacchetti IN USCITA a passare per questa esatta interfaccia
                mSocket.setNetworkInterface(netIf); 

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

        // Messaggio finale (scatola di chiusura) ripetuto per robustezza
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

class Receiver implements Runnable {
    private int mioID;
    private MulticastSocket socket;
    private InetAddress group;
    private int[] nextExpected;
    private Map<Integer, String> history;
    
    // IL BUFFER: Una lista di set (un set per ogni nodo) per i messaggi fuori ordine
    private List<Set<Integer>> bufferFuoriOrdine;

    Receiver(int id, MulticastSocket s, InetAddress g, int total, Map<Integer, String> h) {
        this.mioID = id; this.socket = s; this.group = g; this.history = h;
        
        this.nextExpected = new int[total + 1];
        Arrays.fill(nextExpected, 1);
        
        this.bufferFuoriOrdine = new ArrayList<>();
        for (int i = 0; i <= total; i++) {
            bufferFuoriOrdine.add(new HashSet<>());
        }
    }

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

                    // CASO 1: È il pacchetto che stavamo aspettando!
                    if (idMsg == nextExpected[idMitt]) {
                        nextExpected[idMitt]++; // Andiamo avanti
                        
                        // Svuotiamo il frigo: controlliamo se i pacchetti successivi erano già arrivati in anticipo
                        while (bufferFuoriOrdine.get(idMitt).contains(nextExpected[idMitt])) {
                            bufferFuoriOrdine.get(idMitt).remove(nextExpected[idMitt]);
                            nextExpected[idMitt]++;
                        }
                    } 
                    // CASO 2: È un pacchetto futuro (Rilevato un salto)
                    else if (idMsg > nextExpected[idMitt]) {
                        // Lo mettiamo nel frigo
                        if (!bufferFuoriOrdine.get(idMitt).contains(idMsg)) {
                            bufferFuoriOrdine.get(idMitt).add(idMsg);
                            
                            // Chiediamo SOLO i pacchetti mancanti che non abbiamo ancora nel buffer
                            for (int m = nextExpected[idMitt]; m < idMsg; m++) {
                                if (m <= 100 && !bufferFuoriOrdine.get(idMitt).contains(m)) {
                                    System.out.println("GAP! Chiedo a Nodo " + idMitt + " msg " + m);
                                    invia("LOST:" + idMitt + ":" + m);
                                }
                            }
                        }
                    }
                    // Se idMsg < nextExpected, è un vecchio duplicato e lo ignoriamo.
                } 
                else if (parti[0].equals("LOST")) {
                    int idSmarrito = Integer.parseInt(parti[1]);
                    int msgSmarrito = Integer.parseInt(parti[2]);
                    
                    // Se il messaggio perso è mio e lo ho in cronologia, lo rispedisco
                    if (idSmarrito == mioID && history.containsKey(msgSmarrito)) {
                        invia(history.get(msgSmarrito));
                    }
                }
                
                // Micro-pausa per far respirare la rete locale ed evitare NACK storm
                Thread.sleep(5);

            } catch (IOException | InterruptedException e) { 
                break; 
            }
        }
    }

    private void invia(String testo) {
        try {
            byte[] b = testo.getBytes();
            socket.send(new DatagramPacket(b, b.length, group, 4446));
        } catch (IOException e) {}
    }
}