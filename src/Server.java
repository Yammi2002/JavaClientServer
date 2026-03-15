import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Server di controllo TCP per il coordinamento dei nodi.
 * Gestisce le fasi di avvio e spegnimento dell'intero sistema.
 * * @author Yammi2002
 */
public class Server {
    /**
     * Avvia il server, attende la connessione di tutti i nodi e coordina l'esecuzione.
     */
    public static void main() {
        int porta = 5000;
        int numeroNodiAttesi = 3; 
        List<Socket> listaNodi = new ArrayList<>();

        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            System.out.println("[SERVER] In ascolto sulla porta " + porta);

            // Fase 1: Accettazione connessioni
            while (listaNodi.size() < numeroNodiAttesi) {
                Socket s = serverSocket.accept();
                listaNodi.add(s);
                System.out.println("[SERVER] Nodo connesso (" + listaNodi.size() + "/" + numeroNodiAttesi + ")");
            }

            // Fase 2: Invio segnale di START
            System.out.println("[SERVER] Tutti presenti. Invio START...");
            for (Socket s : listaNodi) {
                new PrintWriter(s.getOutputStream(), true).println("START");
            }

            // Fase 3: Attesa conferma DONE da tutti i nodi
            int completati = 0;
            for (Socket s : listaNodi) {
                Scanner in = new Scanner(s.getInputStream());
                if (in.hasNextLine() && in.nextLine().equals("DONE")) {
                    completati++;
                    System.out.println("[SERVER] Nodo ha confermato ricezione totale (" + completati + "/" + numeroNodiAttesi + ")");
                }
            }

            // Fase 4: Chiusura del sistema
            System.out.println("[SERVER] Tutti i nodi sono allineati. Spengo il sistema.");
            for (Socket s : listaNodi) {
                new PrintWriter(s.getOutputStream(), true).println("SHUTDOWN");
                s.close();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}