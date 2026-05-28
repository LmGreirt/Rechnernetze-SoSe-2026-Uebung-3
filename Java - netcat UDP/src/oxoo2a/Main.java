package oxoo2a;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    private static BufferedReader br = null;
    private static InetAddress peerAddress = null;
    private static int peerPort = -1;

    private static void fatal ( String comment ) {
        System.out.println(comment);
        System.exit(-1);
    }

    // ************************************************************************
    // MAIN
    // ************************************************************************
    public static void main(String[] args) throws IOException {
        if (args.length != 2)
            fatal("Usage: \"<netcat> -l <port>\" or \"netcat <ip> <port>\"");
        int port = Integer.parseInt(args[1]);
        if (args[0].equalsIgnoreCase("-l"))
            listenAndTalk(port);
        else
            connectAndTalk(args[0],port);
    }

    private static final int packetSize = 4096;
    private static final String SEND_USAGE = "Usage: send <target-ip> <target-port> <message>";

    // ************************************************************************
    // listenAndTalk
    // ************************************************************************
    private static void listenAndTalk ( int port ) throws IOException  {
        DatagramSocket s = new DatagramSocket(port);
        System.out.printf("Listening on UDP port %d ...%n", port);
        System.out.println("Type: send <target-ip> <target-port> <message>");
        System.out.println("Or type a plain message to send to the current default peer.");
        System.out.println("Type stop to quit.");
        startChatSession(s, null, -1);
    }

    // ************************************************************************
    // connectAndTalk
    // ************************************************************************
    private static void connectAndTalk ( String other_host, int other_port ) throws IOException {
        InetAddress other_address = InetAddress.getByName(other_host);
        DatagramSocket s = new DatagramSocket();
        setPeer(other_address, other_port);
        System.out.printf("Chat target is %s:%d%n", other_host, other_port);
        System.out.println("Type: send <target-ip> <target-port> <message>");
        System.out.println("Or type a plain message to send to the current default peer.");
        System.out.println("Type stop to quit.");
        startChatSession(s, other_address, other_port);
    }

    private static void startChatSession(DatagramSocket socket, InetAddress initialPeerAddress, int initialPeerPort)
            throws IOException {
        AtomicBoolean running = new AtomicBoolean(true);
        setPeer(initialPeerAddress, initialPeerPort);

        Thread receiver = new Thread(() -> receiveLoop(socket, running));
        receiver.setName("udp-receiver");
        receiver.start();

        Thread sender = new Thread(() -> sendLoop(socket, running));
        sender.setName("udp-sender");
        sender.start();

        try {
            sender.join();
            if (running.get()) {
                // stdin can close while the receive side should continue to run
                receiver.join();
            } else {
                if (!socket.isClosed()) socket.close();
                receiver.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
            if (!socket.isClosed()) socket.close();
        }
    }

    private static void receiveLoop(DatagramSocket socket, AtomicBoolean running) {
        byte[] buffer = new byte[packetSize];
        while (running.get()) {
            DatagramPacket p = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(p);
                if (!hasPeer()) {
                    setPeer(p.getAddress(), p.getPort());
                    System.out.printf("Chat peer discovered: %s:%d%n", p.getAddress().getHostAddress(), p.getPort());
                }
                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                System.out.printf("[%s:%d] %s%n", p.getAddress().getHostAddress(), p.getPort(), line);
                if (line.equalsIgnoreCase("stop")) {
                    running.set(false);
                    if (!socket.isClosed()) socket.close();
                }
            } catch (SocketException e) {
                if (running.get()) {
                    System.out.printf("Socket exception while receiving: %s%n", e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running.get()) {
                    System.out.printf("I/O exception while receiving: %s%n", e.getMessage());
                }
                break;
            }
        }
    }

    private static void sendLoop(DatagramSocket socket, AtomicBoolean running) {
        while (running.get()) {
            String line = readString();
            if (line == null) {
                System.out.println("Input closed. Sending is disabled, receiver stays active.");
                break;
            }

            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.equalsIgnoreCase("stop") || line.equalsIgnoreCase("exit")) {
                running.set(false);
                if (!socket.isClosed()) socket.close();
                break;
            }

            if (line.toLowerCase().startsWith("send ")) {
                handleSendCommand(socket, line);
                continue;
            }

            InetAddress targetAddress;
            int targetPort;
            synchronized (Main.class) {
                targetAddress = peerAddress;
                targetPort = peerPort;
            }

            if (targetAddress == null || targetPort <= 0) {
                System.out.println("No default peer known yet. Use: " + SEND_USAGE);
                continue;
            }

            try {
                byte[] payload = line.getBytes(StandardCharsets.UTF_8);
                DatagramPacket p = new DatagramPacket(payload, payload.length, targetAddress, targetPort);
                socket.send(p);
            } catch (IOException e) {
                if (running.get()) {
                    System.out.printf("I/O exception while sending: %s%n", e.getMessage());
                }
                break;
            }
        }
    }

    private static void handleSendCommand(DatagramSocket socket, String commandLine) {
        String[] parts = commandLine.split("\\s+", 4);
        if (parts.length < 4) {
            System.out.println(SEND_USAGE);
            return;
        }

        InetAddress targetAddress;
        int targetPort;
        try {
            targetAddress = InetAddress.getByName(parts[1]);
            targetPort = Integer.parseInt(parts[2]);
        } catch (Exception e) {
            System.out.println("Invalid target. " + SEND_USAGE);
            return;
        }

        String message = parts[3];
        try {
            byte[] payload = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(payload, payload.length, targetAddress, targetPort);
            socket.send(p);
            setPeer(targetAddress, targetPort);
        } catch (IOException e) {
            System.out.printf("I/O exception while sending command message: %s%n", e.getMessage());
        }
    }

    private static synchronized void setPeer(InetAddress address, int port) {
        peerAddress = address;
        peerPort = port;
    }

    private static synchronized boolean hasPeer() {
        return peerAddress != null && peerPort > 0;
    }

    private static String readString () {
        boolean again = false;
        String input = null;
        do {
            // System.out.print("Input: ");
            try {
                if (br == null)
                    br = new BufferedReader(new InputStreamReader(System.in));
                input = br.readLine();
            }
            catch (Exception e) {
                System.out.printf("Exception: %s\n",e.getMessage());
                again = true;
            }
        } while (again);
        return input;
    }
}
