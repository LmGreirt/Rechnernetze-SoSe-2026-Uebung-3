package oxoo2a;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class Main {

    private static final Map<String, ClientSession> clients = new ConcurrentHashMap<>();
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static BufferedReader br = null;

    private static void fatalUsage () {
        System.out.println("Usage: \"<netcat> -l <port>\"");
        System.exit(-1);
    }

    // ************************************************************************
    // MAIN
    // ************************************************************************
    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !args[0].equalsIgnoreCase("-l")) {
            fatalUsage();
        }
        int port = Integer.parseInt(args[1]);
        chatServer(port);
    }

    // ************************************************************************
    // Server
    // ************************************************************************
    private static void chatServer ( int port ) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.printf("TCP chat server listening on port %d%n", port);
            System.out.println("Commands: send <Empfängername> <Nachricht> | list | stop");

            Thread console = new Thread(() -> serverConsoleLoop(server));
            console.setName("tcp-console");
            console.setDaemon(true);
            console.start();

            while (running.get()) {
                try {
                    Socket client = server.accept();
                    Thread worker = new Thread(() -> handleClient(client));
                    worker.setName("tcp-client-" + client.getPort());
                    worker.setDaemon(true);
                    worker.start();
                } catch (SocketException e) {
                    if (running.get()) {
                        System.out.printf("Socket exception in accept loop: %s%n", e.getMessage());
                    }
                    break;
                }
            }
        } finally {
            shutdownServer();
        }
    }

    private static void serverConsoleLoop(ServerSocket server) {
        while (running.get()) {
            String line = readString();
            if (line == null) {
                shutdownServer(server);
                break;
            }

            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.equalsIgnoreCase("stop") || line.equalsIgnoreCase("exit")) {
                shutdownServer(server);
                break;
            }

            if (line.equalsIgnoreCase("list")) {
                System.out.println("Active clients: " + activeClients());
                continue;
            }

            if (line.toLowerCase().startsWith("send ")) {
                handleConsoleSend(line);
                continue;
            }

            System.out.println("Unknown command. Use: send <Empfängername> <Nachricht>, list, stop");
        }
    }

    private static void handleConsoleSend(String commandLine) {
        String[] parts = commandLine.split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println("Usage: send <Empfängername> <Nachricht>");
            return;
        }

        String recipient = parts[1];
        String message = parts[2];
        ClientSession session = clients.get(recipient);
        if (session == null) {
            System.out.printf("Unknown client '%s'. Active: %s%n", recipient, activeClients());
            return;
        }

        session.sendLine(message);
        System.out.printf("Sent to %s: %s%n", recipient, message);
    }

    private static void handleClient(Socket socket) {
        String registeredName = null;
        ClientSession session = null;
        boolean registered = false;
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String firstLine = reader.readLine();
            if (firstLine == null) {
                return;
            }

            if (!firstLine.regionMatches(true, 0, "register ", 0, 9)) {
                writer.println("ERROR: first line must be register <name>");
                return;
            }

            registeredName = firstLine.substring(9).trim();
            if (registeredName.isEmpty()) {
                writer.println("ERROR: name must not be empty");
                return;
            }

            session = new ClientSession(s, writer);
            ClientSession previous = clients.putIfAbsent(registeredName, session);
            if (previous != null) {
                writer.println("ERROR: name already in use");
                return;
            }
            registered = true;

            System.out.printf("Client registered: %s%n", registeredName);
            writer.println("Registered as " + registeredName);
            writer.println("Available server commands: send <Empfängername> <Nachricht>, list, stop");
            writer.println("Active clients: " + activeClients());

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                System.out.printf("[%s] %s%n", registeredName, line);
                if (line.equalsIgnoreCase("stop") || line.equalsIgnoreCase("exit")) {
                    break;
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                System.out.printf("IOException while handling client %s: %s%n", registeredName, e.getMessage());
            }
        } finally {
            if (registered) {
                clients.remove(registeredName, session);
                System.out.printf("Client disconnected: %s%n", registeredName);
            }
        }
    }

    private static String activeClients() {
        if (clients.isEmpty()) return "(none)";
        return clients.keySet().stream().sorted().collect(Collectors.joining(", "));
    }

    private static void shutdownServer() {
        if (!running.getAndSet(false)) return;
        for (ClientSession session : new ArrayList<>(clients.values())) {
            session.sendLine("Server shutting down");
            session.close();
        }
        clients.clear();
    }

    private static void shutdownServer(ServerSocket server) {
        shutdownServer();
        try {
            if (server != null && !server.isClosed()) server.close();
        } catch (IOException ignored) {
        }
    }

    private static final class ClientSession {
        private final Socket socket;
        private final PrintWriter writer;

        private ClientSession(Socket socket, PrintWriter writer) {
            this.socket = socket;
            this.writer = writer;
        }

        private synchronized void sendLine(String line) {
            writer.println(line);
        }

        private synchronized void close() {
            try {
                if (!socket.isClosed()) socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readString () {
        boolean again = false;
        String input = null;
        do {
            try {
                if (br == null)
                    br = new BufferedReader(new InputStreamReader(System.in));
                input = br.readLine();
            }
            catch (Exception e) {
                System.out.printf("Exception: %s%n",e.getMessage());
                again = true;
            }
        } while (again);
        return input;
    }
}
