import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    public static void main(String[] args) {
        int porta = 5000;
        int numeroNodiAttesi = 3; 
        List<Socket> listaNodi = new ArrayList<>();

        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            System.out.println("[SERVER] In ascolto sulla porta " + porta);

            // 1. Fase di Connessione
            while (listaNodi.size() < numeroNodiAttesi) {
                Socket s = serverSocket.accept();
                listaNodi.add(s);
                System.out.println("[SERVER] Nodo connesso (" + listaNodi.size() + "/" + numeroNodiAttesi + ")");
            }

            // 2. Fase di START
            System.out.println("[SERVER] Tutti presenti. Invio START...");
            for (Socket s : listaNodi) {
                new PrintWriter(s.getOutputStream(), true).println("START");
            }

            // 3. Fase di Attesa COMPLETAMENTO (Affidabilità Totale)
            int completati = 0;
            for (Socket s : listaNodi) {
                Scanner in = new Scanner(s.getInputStream());
                if (in.hasNextLine() && in.nextLine().equals("DONE")) {
                    completati++;
                    System.out.println("[SERVER] Nodo ha confermato ricezione totale (" + completati + "/" + numeroNodiAttesi + ")");
                }
            }

            // 4. Fase di SHUTDOWN
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