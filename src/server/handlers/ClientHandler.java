package server.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import server.Server;
import server.models.FileTransferDetails;
import server.models.TransferContext;
import shared.*;
import shared.messages.*;
import shared.messages.fileTransfer.*;
import shared.messages.privateMessage.*;
import shared.messages.requestList.*;
import shared.messages.rockPaperScissor.*;

import java.io.*;
import java.net.Socket;
import java.util.*;

public class ClientHandler implements Runnable {
    private static final String VERSION = "1.6.0";

    private static final int PING_INTERVAL_MS = 10000;
    private static final int PONG_TIMEOUT_MS = 2000;

    private final Map<String, ClientHandler> clients;
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, String> playerToPlayer;
    private Map<String, String> playerMoves;
    private List<FileTransferDetails> pendingTransfers;

    private Server server;

    private final Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private String username;
    private Timer pingTimer;
    private boolean awaitingPong;

    /**
     * Initializes a new ClientHandler instance for managing communication with a connected client.
     *
     * @param socket The socket connected to the client.
     * @param clients A map of all connected clients, where the key is the username.
     * @param playerToPlayer A map linking players engaged in RPS games.
     * @param playerMoves A map storing moves made by players in ongoing RPS games.
     * @param pendingTransfers A list of pending file transfer requests in the server.
     * @param server The main server instance managing global state.
     */
    public ClientHandler(Socket socket, Map<String, ClientHandler> clients, Map<String, String> playerToPlayer, Map<String, String> playerMoves, List<FileTransferDetails> pendingTransfers, Server server) {
        this.socket = socket;
        this.clients = clients;
        this.playerToPlayer = playerToPlayer;
        this.playerMoves = playerMoves;
        this.pendingTransfers = pendingTransfers;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            setupStreams();
            sendReadyMessage();

            while (true) {
                String input = reader.readLine();
                if (input == null) break;
                handleClientMessage(input);
            }
        } catch (IOException e) {
            System.out.println("Connection error with client " + username + ": " + e.getMessage());
        } finally {
            disconnectClient();
        }
    }

    private void setupStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(socket.getOutputStream(), true);
    }

    private void sendReadyMessage() throws JsonProcessingException {
        Ready readyMessage = new Ready(VERSION);
        sendFormattedMessage(Commands.READY, readyMessage);
    }

    private void handleClientMessage(String input) throws IOException {
        if (input == null || input.isBlank()) {
            sendFormattedMessage(Commands.UNKNOWN_COMMAND, new ParseError());
            return;
        }

        String[] parts = input.split(" ", 2);
        if (parts.length < 2) {
            sendFormattedMessage(Commands.UNKNOWN_COMMAND, new ParseError());
            return;
        }
        String command = parts[0];
        String jsonPayload = parts[1];
        try {
            System.out.println(jsonPayload);
            switch (command) {
                case Commands.ENTER -> handleLogin(jsonPayload);
                case Commands.BROADCAST_REQ -> handleBroadcast(jsonPayload);
                case Commands.PONG -> handlePong();
                case Commands.BYE -> handleLogout();
                case Commands.LIST_REQ -> sendListOfConnectedClients();
                case Commands.PRIVATE_MSG_REQ -> handlePrivateMessage(jsonPayload);
                case Commands.RPS_START_REQ -> handleRpsStart(jsonPayload);
                case Commands.RPS_INVITE_RESP -> handleRpsInviteResponse(jsonPayload);
                case Commands.RPS_MOVE_REQ -> handleRpsMove(jsonPayload);
                case Commands.FILE_TRANSFER_REQ -> handleFileTransferRequest(jsonPayload);
                case Commands.FILE_TRANSFER_RESP -> handleFileTransferResponse(jsonPayload);
                default -> sendFormattedMessage(Commands.UNKNOWN_COMMAND, new ParseError());

            }
        } catch (JsonProcessingException e) {
            sendFormattedMessage(Commands.PARSE_ERROR, new ParseError());
        }
    }

    /**
     * Processes a response to a file transfer request (accept or decline).
     * Notifies the sender of the decision and sets up a transfer context if accepted.
     *
     * @param jsonPayload The JSON payload containing the response details.
     * @throws JsonProcessingException If the payload cannot be parsed.
     */
    private void handleFileTransferResponse(String jsonPayload) throws JsonProcessingException {
        FileTransferResp response = mapper.readValue(jsonPayload, FileTransferResp.class);

        FileTransferDetails matched = null;
        for (FileTransferDetails ftd : pendingTransfers) {
            if (ftd.getReceiver().equals(username)) {
                matched = ftd;
                break;
            }
        }
        if (matched == null) {
            return;
        }

        ClientHandler senderHandler = clients.get(matched.getSender());
        ClientHandler receiverHandler = clients.get(matched.getReceiver());

        if (response.status().equals("ACCEPT")) {
            String uuid = generateUUID();
            if (receiverHandler != null) {
                TransferContext transferContext = new TransferContext();
                this.server.getOngoingTransfers().put(uuid, transferContext);
                senderHandler.sendFormattedMessage(Commands.FILE_TRANSFER_READY, new FileTransferReady(uuid, "s", matched.getChecksum(), matched.getFilename()));
                receiverHandler.sendFormattedMessage(Commands.FILE_TRANSFER_READY, new FileTransferReady(uuid, "r", matched.getChecksum(), matched.getFilename()));
            }
        } else if (response.status().equals("DECLINE")) {
            senderHandler.sendFormattedMessage(Commands.FILE_TRANSFER_RESP, new FileTransferResp("DECLINE", 0));
        }
        pendingTransfers.remove(matched);
    }

    /**
     * Generates a unique identifier for a file transfer session.
     *
     * @return A randomly generated UUID string.
     */
    private String generateUUID() {
        UUID uuid = UUID.randomUUID();
        return uuid.toString();
    }

    /**
     * Handles a client's request to transfer a file to another client.
     * Validates the request and forwards it to the receiver or responds with an error.
     *
     * @param jsonPayload The JSON payload containing the file transfer details.
     * @throws JsonProcessingException If the payload cannot be parsed.
     */
    private void handleFileTransferRequest(String jsonPayload) throws JsonProcessingException {
        if (username == null) {
            sendFormattedMessage(Commands.FILE_TRANSFER_RESP, new FileTransferResp("ERROR", 13000));
            return;
        }

        FileTransferReq request = mapper.readValue(jsonPayload, FileTransferReq.class);
        String sender = request.sender();
        String receiver = request.receiver();
        String filename = request.filename();
        String checksum = request.checksum();

        if (username.equals(receiver)) {
            sendFormattedMessage(Commands.FILE_TRANSFER_RESP, new FileTransferResp("ERROR", 13002));
            return;
        }

        ClientHandler receiverHandler = clients.get(receiver);
        if (receiverHandler == null) {
            sendFormattedMessage(Commands.FILE_TRANSFER_RESP, new FileTransferResp("ERROR", 13001));
            return;
        }

        pendingTransfers.add(new FileTransferDetails(sender, receiver, filename, checksum));
        sendFormattedMessage(Commands.FILE_TRANSFER_RESP, new FileTransferResp("OK", 0));
        receiverHandler.sendFormattedMessage(Commands.FILE_TRANSFER_REQ, new FileTransferReq(sender, receiver, filename, checksum));
    }

    /**
     * Handles a request to start a Rock, Paper, Scissors game.
     * Validates the request and sends an invitation to the intended receiver or responds with an error.
     *
     * @param jsonPayload The JSON payload containing the game start details.
     * @throws JsonProcessingException If the payload cannot be parsed.
     */
    private void handleRpsStart(String jsonPayload) throws JsonProcessingException {
        if (username == null) {
            sendFormattedMessage(Commands.RPS_START_RESP, new RpsStartResp("ERROR", 11001, null, null));
            return;
        }

        RpsStartReq request = mapper.readValue(jsonPayload, RpsStartReq.class);
        String receiver = request.receiver();

        if (receiver.equals(username)) {
            sendFormattedMessage(Commands.RPS_START_RESP, new RpsStartResp("ERROR", 11003, null, null));
            return;
        }

        ClientHandler receiverHandler = clients.get(receiver);
        if (receiverHandler == null) {
            sendFormattedMessage(Commands.RPS_START_RESP, new RpsStartResp("ERROR", 11002, null, null));
            return;
        }

        if (playerToPlayer.containsKey(username) || playerToPlayer.containsKey(receiver)) {
            String conflictPlayer1 = null;
            String conflictPlayer2 = null;

            for (Map.Entry<String, String> entry : playerToPlayer.entrySet()) {
                if (entry.getKey().equals(username) || entry.getValue().equals(username) ||
                        entry.getKey().equals(receiver) || entry.getValue().equals(receiver)) {
                    conflictPlayer1 = entry.getKey();
                    conflictPlayer2 = entry.getValue();
                    break;
                }
            }

            sendFormattedMessage(Commands.RPS_START_RESP, new RpsStartResp("ERROR", 11004, conflictPlayer1, conflictPlayer2));
            return;
        }

        playerToPlayer.put(username, receiver);
        playerToPlayer.put(receiver, username);

        sendFormattedMessage(Commands.RPS_START_RESP, new RpsStartResp("OK", 0, null, null));
        receiverHandler.sendFormattedMessage(Commands.RPS_INVITE, new RpsInvite(username));
    }

    /**
     * Handles a client's move during an RPS game.
     * Validates the move and determines the game result if both players have made their moves.
     *
     * @param jsonPayload The JSON payload containing the player's move.
     * @throws JsonProcessingException If the payload cannot be parsed.
     */
    private void handleRpsMove(String jsonPayload) throws JsonProcessingException {
        if (!playerToPlayer.containsKey(username)) {
            sendFormattedMessage(Commands.RPS_MOVE_RESP, new RpsMoveResp("ERROR", 11005));
            return;
        }

        String opponent = playerToPlayer.get(username);

        RpsMove move = mapper.readValue(jsonPayload, RpsMove.class);
        String choice = move.choice();

        playerMoves.put(username, choice);
        sendFormattedMessage(Commands.RPS_MOVE_RESP, new RpsMoveResp("OK", 0));

        if (opponent != null && playerMoves.containsKey(opponent)) {
            resolveGame(username, opponent);
        }
    }

    /**
     * Processes a response to an RPS game invitation from the receiver (accept or decline).
     * Updates game state and notifies the inviter of the decision.
     *
     * @param jsonPayload The JSON payload containing the response details.
     * @throws JsonProcessingException If the payload cannot be parsed.
     */
    private void handleRpsInviteResponse(String jsonPayload) throws JsonProcessingException {
        if (username == null) {
            System.out.println("Username not set for the client");
            return;
        }

        RpsInviteResp response = mapper.readValue(jsonPayload, RpsInviteResp.class);
        String opponent = playerToPlayer.get(username);

        if (opponent == null) {
            System.out.println("Opponent is null");
            return;
        }

        ClientHandler opponentHandler = clients.get(opponent);

        switch (response.status()) {
            case "ACCEPT":
                sendFormattedMessage(Commands.RPS_READY, null);
                opponentHandler.sendFormattedMessage(Commands.RPS_READY, null);
                break;
            case "DECLINE":
                playerToPlayer.remove(username);
                playerToPlayer.remove(opponent);
                opponentHandler.sendFormattedMessage(Commands.RPS_INVITE_DECLINED, new RpsInviteDeclined());
                sendFormattedMessage(Commands.RPS_INVITE_DECLINED, new RpsInviteDeclined());
                break;
        }
    }

    /**
     * Determines the result of an RPS game based on the moves of both players.
     * Notifies both players of the result and resets their game state.
     *
     * @param player1 The username of the first player.
     * @param player2 The username of the second player.
     * @throws JsonProcessingException If the result message cannot be serialized.
     */
    private void resolveGame(String player1, String player2) throws JsonProcessingException {
        String move1 = playerMoves.remove(player1);
        String move2 = playerMoves.remove(player2);

        String winner = null;
        if (!move1.equals(move2)) {
            if ((move1.equals("/r") && move2.equals("/s")) ||
                    (move1.equals("/s") && move2.equals("/p")) ||
                    (move1.equals("/p") && move2.equals("/r"))) {
                winner = player1;
            } else {
                winner = player2;
            }
        }

        playerToPlayer.remove(player1);
        playerToPlayer.remove(player2);

        Map<String, String> choices = Map.of(player1, move1, player2, move2);

        RpsResult result = new RpsResult(winner, choices);
        clients.get(player1).sendFormattedMessage(Commands.RPS_RESULT, result);
        clients.get(player2).sendFormattedMessage(Commands.RPS_RESULT, result);
    }

    /**
     * Handles private messages between two clients.
     * Validates the receiver and forwards the message or responds with an error.
     *
     * @param jsonPayload The JSON payload containing the private message details.
     * @throws IOException If the payload cannot be parsed or an I/O error occurs.
     */
    private void handlePrivateMessage(String jsonPayload) throws IOException {
        if (username == null) {
            sendFormattedMessage(Commands.PRIVATE_MSG_RESP, new PrivateMsgResp("ERROR", 10001));
            return;
        }

        PrivateMsgReq privateMsgReq = mapper.readValue(jsonPayload, PrivateMsgReq.class);
        String receiverUsername = privateMsgReq.receiver();
        String messageContent = privateMsgReq.message();

        if (receiverUsername.equals(username)) {
            sendFormattedMessage(Commands.PRIVATE_MSG_RESP, new PrivateMsgResp("ERROR", 10003));
            return;
        }

        ClientHandler receiver = clients.get(receiverUsername);
        if (receiver == null) {
            sendFormattedMessage(Commands.PRIVATE_MSG_RESP, new PrivateMsgResp("ERROR", 10002));
            return;
        }

        PrivateMsg privateMsg = new PrivateMsg(username, messageContent);
        receiver.sendFormattedMessage(Commands.PRIVATE_MSG, privateMsg);
        sendFormattedMessage(Commands.PRIVATE_MSG_RESP, new PrivateMsgResp("OK", 0));
    }

    private void handleLogin(String jsonPayload) throws IOException {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            sendFormattedMessage(Commands.ENTER_RESP, new EnterResp("ERROR", 5001));
            return;
        }
        if (username != null) {
            sendFormattedMessage(Commands.ENTER_RESP, new EnterResp("ERROR", 5002));
            return;
        }

        Enter enterMessage = mapper.readValue(jsonPayload, Enter.class);
        String newUsername = enterMessage.username();

        if (clients.containsKey(newUsername)) {
            sendFormattedMessage(Commands.ENTER_RESP, new EnterResp("ERROR", 5000));
        } else if (!newUsername.matches("[A-Za-z0-9_]{3,14}")) {
            sendFormattedMessage(Commands.ENTER_RESP, new EnterResp("ERROR", 5001));
        } else {
            this.username = newUsername;
            clients.put(newUsername, this);

            sendFormattedMessage(Commands.ENTER_RESP, new EnterResp("OK", 0));
            broadcastMessage(Commands.JOINED, new Joined(newUsername), this);
            startPingTimer();
        }
    }

    /**
     * Starts a periodic "PING" timer to check the heartbeat of the client connection.
     * If no "PONG" is received within a timeout period, the client is disconnected.
     */
    private void startPingTimer() {
        pingTimer = new Timer();
        pingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (awaitingPong) {
                    sendHangup();
                    disconnectClient();
                } else {
                    sendPing();
                }
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS);
    }

    /**
     * Sends a "PING" message to the client and sets a timeout for the corresponding "PONG" response.
     * If the client does not respond, the connection is terminated.
     */
    private void sendPing() {
        try {
            awaitingPong = true;

            sendFormattedMessage(Commands.PING, new Ping());
            System.out.println(Commands.PING + " -> " + username);
            // Start a timeout for PONG
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    if (awaitingPong) {
                        sendHangup();
                        System.out.println("[HANGUP]" + username);
                        disconnectClient();
                    }
                }
            }, PONG_TIMEOUT_MS);
        } catch (Exception e) {
            System.out.println("Error sending PING: " + e.getMessage());
        }
    }

    /**
     * Sends a "HANGUP" message to the client indicating that the server is closing the connection
     * due to a failed "PING"/"PONG" check.
     *
     */
    private void sendHangup() {
        try {
            sendFormattedMessage(Commands.HANGUP, new Hangup("ERROR", 7000));
        } catch (JsonProcessingException e) {
            System.out.println("Error sending HANGUP to " + username + ": " + e.getMessage());
        }
    }

    /**
     * Processes a "PONG" message from the client in response to a "PING" sent by the server.
     * Resets the pong timeout to maintain the connection check.
     *
     * @throws JsonProcessingException If the PONG response cannot be serialized.
     */
    private void handlePong() throws JsonProcessingException {
        if (!awaitingPong) {
            System.out.println("Unexpected PONG from " + username);
            sendFormattedMessage(Commands.PONG_ERROR, new PongError(8000));
        } else {
            awaitingPong = false;
            System.out.println(username + " -> PONG");
        }
    }

    /**
     * Handles broadcast messages sent by a client.
     * Broadcasts the message to all connected clients except the sender.
     *
     * @param jsonPayload The JSON payload containing the broadcast message.
     * @throws JsonProcessingException If the payload cannot be parsed.
     */
    private void handleBroadcast(String jsonPayload) throws JsonProcessingException {
        if (username == null) {
            sendFormattedMessage(Commands.BROADCAST_RESP, new BroadcastResp("ERROR", 6000));
        } else {
            BroadcastReq broadcastReq = mapper.readValue(jsonPayload, BroadcastReq.class);
            String messageContent = broadcastReq.message();
            broadcastMessage(Commands.BROADCAST, new Broadcast(username, messageContent), this);
            sendFormattedMessage(Commands.BROADCAST_RESP, new BroadcastResp("OK", 0));
        }
    }

    private void handleLogout() throws JsonProcessingException {
        if (username != null) {
            clients.remove(username);
            broadcastMessage(Commands.LEFT, new Joined(username), this);
        }
        sendFormattedMessage(Commands.BYE_RESP, new BroadcastResp("OK", 0));
        disconnectClient();
    }

    private void broadcastMessage(String command, Object message, ClientHandler excludeClient) throws JsonProcessingException {
        String formattedMessage = formatMessage(command, message);
        for (ClientHandler client : clients.values()) {
            if (client != excludeClient) {
                client.writer.println(formattedMessage);
            }
        }
    }

    /**
     * Sends the list of all connected clients to the requesting client (including the client that makes the request).
     *
     * @throws JsonProcessingException If the client list cannot be serialized.
     */
    private void sendListOfConnectedClients() throws JsonProcessingException {
        if (username == null) {
            sendFormattedMessage(Commands.LIST_RESP, new ListResp("ERROR", 9000, null));
            return;
        }

        List<String> clientList = List.copyOf(clients.keySet());
        sendFormattedMessage(Commands.LIST_RESP, new ListResp("OK", 0, clientList));
    }

    /**
     * Sends a formatted message to the client by combining a command and its JSON payload.
     *
     * @param command The command type ("PING", "PRIVATE_MSG", etc.).
     * @param message The message object to be serialized and sent.
     * @throws JsonProcessingException If the message cannot be serialized.
     */
    private void sendFormattedMessage(String command, Object message) throws JsonProcessingException {
        writer.println(formatMessage(command, message));
    }

    /**
     * Formats a command and its JSON payload into a single string for transmission.
     *
     * @param command The command type.
     * @param message The message object to be serialized.
     * @return A formatted string combining the command and serialized JSON payload.
     * @throws JsonProcessingException If the message cannot be serialized.
     */
    private String formatMessage(String command, Object message) throws JsonProcessingException {
        try {
            return command + " " + mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            System.out.println("Error formatting message: " + e.getMessage());
            return "";
        }
    }

    private void disconnectClient() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.out.println("Error closing socket: " + e.getMessage());
        }

        if (username != null) {
            String opponent = playerToPlayer.remove(username);
            if (opponent != null) {
                playerToPlayer.remove(opponent);
                ClientHandler opponentHandler = clients.get(opponent);
                if (opponentHandler != null) {
                    try {
                        opponentHandler.sendFormattedMessage(Commands.RPS_INVITE_DECLINED, new RpsInviteDeclined());
                    } catch (JsonProcessingException e) {
                        System.out.println("Error notifying opponent about disconnection: " + e.getMessage());
                    }
                }
            }

            clients.remove(username);
        }
    }

}