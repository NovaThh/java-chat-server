package server;


import server.handlers.ClientHandler;
import server.handlers.FileTransferHandler;
import server.models.FileTransferDetails;
import server.models.TransferContext;
import shared.Utils;

import java.io.IOException;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class Server {
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, String> playerToPlayer = new HashMap<>();
    private final Map<String, String> playerMoves = new ConcurrentHashMap<>();
    private final List<FileTransferDetails> pendingTransfers = new ArrayList<>();
    private static Map<String, TransferContext> ongoingTransfers = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        new Server().start();
    }

    public void start() {
        try {
            ServerSocket serverSocket = new ServerSocket(Utils.SERVER_PORT);
            System.out.println("Server is running on port " + Utils.SERVER_PORT);

            ServerSocket fileTransferSocket = new ServerSocket(Utils.FILE_TRANSFER_PORT);
            System.out.println("File Transfer running on port " + Utils.FILE_TRANSFER_PORT);

            Thread fileAcceptor = new Thread(() -> {
                while (!fileTransferSocket.isClosed()) {
                    try {
                        Socket ftSocket = fileTransferSocket.accept();
                        new Thread(new FileTransferHandler(ftSocket, this)).start();
                    } catch (IOException e) {
                        System.out.println("File transfer accept error: " + e.getMessage());
                    }
                }
            });
            fileAcceptor.start();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler clientHandler = new ClientHandler(clientSocket, clients, playerToPlayer, playerMoves, pendingTransfers, this);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }

    public Map<String, TransferContext> getOngoingTransfers() {
        return ongoingTransfers;
    }
}