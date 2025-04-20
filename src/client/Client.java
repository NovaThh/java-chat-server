package client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import shared.Commands;
import shared.Utils;
import shared.messages.*;
import shared.messages.fileTransfer.FileTransferReady;
import shared.messages.fileTransfer.FileTransferReq;
import shared.messages.fileTransfer.FileTransferResp;
import shared.messages.privateMessage.PrivateMsg;
import shared.messages.privateMessage.PrivateMsgReq;
import shared.messages.privateMessage.PrivateMsgResp;
import shared.messages.requestList.ListReq;
import shared.messages.requestList.ListResp;
import shared.messages.rockPaperScissor.*;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Client {
    private static final String SERVER_ADDRESS = "127.0.0.1";

    private static Socket socket;
    private static BufferedReader userReader;
    private static BufferedReader serverReader;
    private static ObjectMapper mapper;
    private static PrintWriter writer;

    private static String username;
    private static List<FileTransferReq> incomingRequests = new ArrayList<>();
    private static Map<String, String> filePathMap = new HashMap<>();

    public static void main(String[] args) {
        try {
            socket = new Socket(SERVER_ADDRESS, Utils.SERVER_PORT);

            mapper = new ObjectMapper();

            if (!initializeConnection()) return;

            userReader = new BufferedReader(new InputStreamReader(System.in));
            writer = new PrintWriter(socket.getOutputStream(), true);

            String username = handleUserLogin();
            if (username == null) return;

            startChatMode();
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static boolean initializeConnection() throws IOException {
        serverReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String serverResponse = serverReader.readLine();

        if (serverResponse == null) {
            System.out.println("Failed to connect: No response from server.");
            return false;
        }

        try {
            String[] parts = serverResponse.split(" ", 2);
            if (parts.length < 2 || !Commands.READY.equals(parts[0])) {
                System.out.println("Unexpected response from server.");
                return false;
            }

            Ready readyMessage = mapper.readValue(parts[1], Ready.class);
            System.out.println("Server connected successfully! Version: " + readyMessage.version());
            return true;
        } catch (JsonProcessingException e) {
            System.out.println("Failed to parse server response: " + e.getMessage());
            return false;
        }
    }

    private static String handleUserLogin() throws IOException {
        while (true) {
            System.out.print("Enter username: ");
            String usernameInput = userReader.readLine();

            Enter enterMessage = new Enter(usernameInput);
            sendCommand(Commands.ENTER, enterMessage);

            String serverResponse = serverReader.readLine();
            if (serverResponse == null) {
                System.out.println("No response from server. Exiting...");
                return null;
            }

            String[] parts = serverResponse.split(" ", 2);
            if (parts.length < 2 || !Commands.ENTER_RESP.equals(parts[0])) {
                System.out.println("Unexpected response from server.");
                continue;
            }

            EnterResp enterResp = mapper.readValue(parts[1], EnterResp.class);
            if (enterResp.status().equals("OK")) {
                System.out.println("Logged in as " + usernameInput);
                username = usernameInput;
                return usernameInput;
            }

            handleLoginError(enterResp.code());
        }
    }

    private static void handleLoginError(int errorCode) {
        switch (errorCode) {
            case 5000 -> System.out.println("User with this name already exists.");
            case 5001 ->
                    System.out.println("A username may only consist of 3-14 characters, numbers, and underscores.");
            case 5002 -> System.out.println("User is already logged in.");
            default -> System.out.println("Unknown error occurred.");
        }
    }

    private static void startChatMode() throws IOException {
        System.out.println(" You are now in chat mode.");
        printHelpMenu();

        Thread listenerThread = createListenerThread();
        listenerThread.start();

        handleUserCommands();
    }

    private static Thread createListenerThread() {
        return new Thread(() -> {
            try {
                String serverMessage;
                while ((serverMessage = serverReader.readLine()) != null) {
                    processServerMessage(serverMessage);
                }
            } catch (IOException e) {
                System.out.println("Connection to server lost: " + e.getMessage());
            }
            closeConnection();
            System.exit(0);
        });
    }

    /**
     * Processes a single message received from the server.
     * Dispatches the message to the appropriate handler based on its command type.
     *
     * @param serverMessage The message received from the server.
     * @throws IOException If an error occurs during message parsing or handling.
     */
    private static void processServerMessage(String serverMessage) throws IOException {
        String[] parts = serverMessage.split(" ", 2);
        String command = parts[0];
        String jsonPayload = parts[1];

        switch (command) {
            case Commands.PING -> sendPong();
            case Commands.HANGUP -> handleHangup();
            case Commands.BROADCAST_RESP -> sendingBroadcast(jsonPayload);
            case Commands.BROADCAST -> printMessage(jsonPayload);
            case Commands.JOINED -> informJoinedClient(jsonPayload);
            case Commands.LEFT -> informLeftClient(jsonPayload);
            case Commands.BYE_RESP -> closeConnection();
            case Commands.LIST_RESP -> handleListOfConnectedClients(jsonPayload);
            case Commands.PRIVATE_MSG -> printPrivateMessage(jsonPayload);
            case Commands.PRIVATE_MSG_RESP -> privateMessageErrors(jsonPayload);
            case Commands.RPS_START_RESP -> handleRpsStartResponse(jsonPayload);
            case Commands.RPS_INVITE -> handleRpsInvite(jsonPayload);
            case Commands.RPS_INVITE_DECLINED -> System.out.println("Game invitation declined.");
            case Commands.RPS_READY -> System.out.println("Please select your move: /r, /p, /s");
            case Commands.RPS_MOVE_RESP -> handleMoveResponse(jsonPayload);
            case Commands.RPS_RESULT -> handleRpsResult(jsonPayload);
            case Commands.FILE_TRANSFER_REQ -> handleIncomingFileTransferRequest(jsonPayload);
            case Commands.FILE_TRANSFER_RESP -> handleFileRequestResponse(jsonPayload);
            case Commands.FILE_TRANSFER_READY -> fileTransferProcess(jsonPayload);
            default -> System.out.println("Unknown server message: " + serverMessage);
        }
    }

    /**
     * Handles the file transfer process after receiving a `FILE_TRANSFER_READY` command from the server.
     * Starts a new thread for sending or receiving the file.
     *
     * @param jsonPayload The JSON payload containing file transfer details.
     * @throws JsonProcessingException If the JSON payload cannot be parsed.
     */
    private static void fileTransferProcess(String jsonPayload) throws JsonProcessingException {
        FileTransferReady fileTransferReady = mapper.readValue(jsonPayload, FileTransferReady.class);
        String uuid = fileTransferReady.uuid();
        String type = fileTransferReady.type();
        String checksum = fileTransferReady.checksum();
        String filename = fileTransferReady.filename();

        new Thread(() -> {
            try (Socket transferSocket = new Socket(SERVER_ADDRESS, Utils.FILE_TRANSFER_PORT);
                 InputStream transferInputStream = transferSocket.getInputStream();
                 OutputStream transferOutputStream = transferSocket.getOutputStream()) {
                if (type.equals("s")) {
                    String path = filePathMap.get(filename);
                    if (path == null) {
                        System.out.println("No stored path for filename " + filename);
                        return;
                    }
                    sendFile(uuid, path, transferOutputStream);
                } else if (type.equals("r")) {
                    receiveFile(uuid, checksum, filename, transferInputStream, transferOutputStream);
                }
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }).start();
    }

    private static void handleUserCommands() throws IOException {
        while (true) {
            String input = userReader.readLine();
            if (input.startsWith("@")) {
                String[] parts = input.split(" ", 2);
                if (parts.length < 2) {
                    System.out.println("Invalid format. Use @username <message>");
                    continue;
                }
                String receiver = parts[0].substring(1);
                String messageContent = parts[1];
                sendCommand(Commands.PRIVATE_MSG_REQ, new PrivateMsgReq(receiver, messageContent));
            } else if (input.startsWith("/send")) {
                requestFileTransfer(input);
            } else if (input.startsWith("/a ")) {
                processFileRequest(input, true);
            } else if (input.startsWith("/d ")) {
                processFileRequest(input, false);
            } else {
                switch (input) {
                    case "/exit" -> {
                        sendCommand(Commands.BYE, new Bye());
                        closeConnection();
                    }
                    case "/help" -> printHelpMenu();
                    case "/all" -> sendCommand(Commands.LIST_REQ, new ListReq());
                    case "/rps" -> initiateRpsGame();
                    case "/y" -> processRpsInvitation(true);
                    case "/n" -> processRpsInvitation(false);
                    case "/r" -> sendMove("/r");
                    case "/p" -> sendMove("/p");
                    case "/s" -> sendMove("/s");
                    case "/files" -> showFileRequests();
                    default -> sendCommand(Commands.BROADCAST_REQ, new BroadcastReq(input));
                }
            }
        }
    }

    /**
     * Sends a file to the server as part of a file transfer process.
     *
     * @param uuid The unique identifier for the file transfer.
     * @param filePath The path to the file being sent.
     * @param transferOutputStream The output stream for sending the file data.
     * @throws IOException If an error occurs during file transfer.
     */
    private static void sendFile(String uuid, String filePath, OutputStream transferOutputStream) throws IOException {
        String header = uuid + "s";
        transferOutputStream.write(header.getBytes());
        transferOutputStream.flush();

        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("Invalid file.");
            return;
        }

        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            fileInputStream.transferTo(transferOutputStream);
        } catch (IOException e) {
            System.out.println("Error while sending file: " + e.getMessage());
        }

        transferOutputStream.close();
        System.out.println("All file bytes sent successfully. Sender's socket closed");
    }

    /**
     * Receives a file from the server and saves it locally.
     * Ensures that the file has a unique name to avoid overwriting existing files.
     *
     * @param uuid The unique identifier for the file transfer.
     * @param expectedChecksum The expected checksum of the file for validation.
     * @param filename The original name of the file.
     * @param transferInputStream The input stream for receiving the file data.
     * @param transferOutputStream The output stream for sending control data.
     * @throws IOException If an error occurs during file transfer or saving.
     */

    private static void receiveFile(String uuid, String expectedChecksum, String filename, InputStream transferInputStream, OutputStream transferOutputStream) throws IOException {
        String header = uuid + "r";
        transferOutputStream.write(header.getBytes());
        transferOutputStream.flush();

        File downloadDir = new File("src\\client\\downloadedFiles");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
            System.out.println("New path created at: " + downloadDir.getAbsolutePath());
        }

        File outFile = generateUniqueFile(downloadDir, filename);
        try (FileOutputStream fileOutputStream = new FileOutputStream(outFile)) {
            transferInputStream.transferTo(fileOutputStream);
            System.out.println("Downloading...");

            System.out.println("Checking checksum...");
            if (expectedChecksum != null) {
                String actualChecksum = Utils.calculateFileChecksum(outFile.getPath());
                if (expectedChecksum.equals(actualChecksum)) {
                    System.out.println("File download complete. Saved to: " + outFile.getAbsolutePath());
                } else {
                    System.out.println("Checksum mismatch! Expected: " + expectedChecksum);
                    System.out.println("Actual: " + actualChecksum);
                }
            }
            closeFileConnection(transferOutputStream, transferInputStream);
        } catch (IOException e) {
            System.out.println("Error writing downloaded file: " + e.getMessage());
        }
    }

    /**
     * Generates a unique file in the target directory by appending a numeric suffix if necessary.
     *
     * @param directory The directory where the file will be saved.
     * @param filename  The original filename.
     * @return A File object with a unique filename.
     */
    private static File generateUniqueFile(File directory, String filename) {
        String baseName = filename;
        String extension = "";

        // Split the filename into base and extension
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
            baseName = filename.substring(0, lastDotIndex);
            extension = filename.substring(lastDotIndex);
        }

        File uniqueFile = new File(directory, filename);
        int count = 1;

        // Keep incrementing the suffix until the filename is unique
        while (uniqueFile.exists()) {
            String newFilename = baseName + "(" + count + ")" + extension;
            uniqueFile = new File(directory, newFilename);
            count++;
        }

        return uniqueFile;
    }

    /**
     * Processes the server's response to a file transfer request.
     * @param jsonPayload The JSON payload containing the server's response.
     * @throws JsonProcessingException If the JSON payload cannot be parsed.
     */

    private static void handleFileRequestResponse(String jsonPayload) throws JsonProcessingException {
        FileTransferResp fileTransferResp = mapper.readValue(jsonPayload, FileTransferResp.class);
        if (fileTransferResp.status().equals("OK")) {
            System.out.println("File transfer request sent ✔");
        } else if (fileTransferResp.status().equals("DECLINE")) {
            System.out.println("File request declined.");
        } else {
            switch (fileTransferResp.code()) {
                case 13000 -> System.out.println("Please log in first.");
                case 13001 -> System.out.println("No receiver found.");
                case 13002 -> System.out.println("Can't send the file to yourself.");
            }
        }
    }

    /**
     * Processes an incoming file request from the server.
     * Allows the user to accept or decline the request and notifies the server of their decision.
     *
     * @param input The user's input containing the sender and filename.
     * @param accept `true` to accept the request, `false` to decline.
     * @throws JsonProcessingException If the server response cannot be parsed.
     */
    private static void processFileRequest(String input, boolean accept) throws JsonProcessingException {
        String[] parts = input.split(" ", 3);
        if (parts.length != 3) {
            System.out.println("Invalid command. Use /accept <sender> <filename> or /decline <sender> <filename>.");
            return;
        }

        String sender = parts[1];
        String filename = parts[2];
        FileTransferReq request = null;

        for (FileTransferReq req : incomingRequests) {
            if (req.filename().equals(filename)) {
                request = req;
                break;
            }
        }

        if (request == null) {
            System.out.println("No file request found.");
            return;
        }

        incomingRequests.remove(request);

        if (accept) {
            System.out.println("Accepted file " + filename + " from " + sender);
            sendCommand(Commands.FILE_TRANSFER_RESP, new FileTransferResp("ACCEPT", 0));
        } else {
            System.out.println("Declined file  " + filename + " from " + sender);
            sendCommand(Commands.FILE_TRANSFER_RESP, new FileTransferResp("DECLINE", 0));
        }
    }

    private static void showFileRequests() {
        if (incomingRequests.isEmpty()) {
            System.out.println("No request to show.");
        } else {
            System.out.println("--- File requests ---");
            int counter = 1;
            for (FileTransferReq req : incomingRequests) {
                System.out.println(counter + ". From: " + req.sender() + ", filename: " + req.filename());
                counter++;
            }
        }
    }

    /**
     * Sends a file transfer request to the server.
     *
     * @param input The user's input containing the receiver and file path (this file path is absolute).
     * @throws JsonProcessingException If the file transfer request cannot be serialized.
     */
    private static void requestFileTransfer(String input) throws JsonProcessingException {
        String[] parts = input.split(" ", 3);
        if (parts.length != 3) {
            System.out.println("Invalid command. Use: /send-file <receiver> <file-path>");
            return;
        }

        String receiver = parts[1];
        String filePath = parts[2];
        File file = new File(filePath);

        if (!file.exists() || !file.isFile()) {
            System.out.println("File does not exist or is invalid");
            return;
        }

        String filename = file.getName();
        String checksum = Utils.calculateFileChecksum(filePath);

        filePathMap.put(filename, filePath);

        FileTransferReq fileTransferReq = new FileTransferReq(username, receiver, filename, checksum);
        sendCommand(Commands.FILE_TRANSFER_REQ, fileTransferReq);

    }

    /**
     * Handles a new file transfer request received from the server.
     * Adds the request to the incoming requests list and notifies the user.
     *
     * @param jsonPayload The JSON payload containing file transfer details.
     * @throws IOException If the JSON payload cannot be parsed.
     */
    private static void handleIncomingFileTransferRequest(String jsonPayload) throws IOException {
        FileTransferReq req = mapper.readValue(jsonPayload, FileTransferReq.class);
        incomingRequests.add(req);
        System.out.println("New file transfer request from: " + req.sender());
    }

    /**
     * Handles the server's response to the user's move during an RPS game.
     * If the status is "OK", the move is successfully recorded by the server.
     * @param jsonPayload The JSON payload containing the server's response to the move.
     * @throws JsonProcessingException If the JSON payload cannot be parsed.
     */
    private static void handleMoveResponse(String jsonPayload) throws JsonProcessingException {
        RpsMoveResp rpsMoveResp = mapper.readValue(jsonPayload, RpsMoveResp.class);
        if (rpsMoveResp.status().equals("OK")) {
            System.out.println("Move sent ✔");
        } else if (rpsMoveResp.status().equals("ERROR") && rpsMoveResp.code() == 11005) {
            System.out.println("No ongoing game.");
        } else {
            System.out.println("Unknown Move response from Server");
        }
    }

    /**
     * Handles the server's response to an RPS game initiation request.
     * Happy Flow: If the response status is "OK", the invitation is successfully sent to the opponent.
     * Unhappy Flow: Displays appropriate error messages based on error codes returned by the server.
     * @param jsonPayload The JSON payload containing the server's response.
     * @throws JsonProcessingException If the JSON payload cannot be parsed.
     */
    private static void handleRpsStartResponse(String jsonPayload) throws JsonProcessingException {
        RpsStartResp rpsStartResp = mapper.readValue(jsonPayload, RpsStartResp.class);
        if (rpsStartResp.status().equals("ERROR")) {
            switch (rpsStartResp.code()) {
                case 11001 -> System.out.println("You need to log in first. Please try again");
                case 11002 -> System.out.println("No opponent found");
                case 11003 -> System.out.println("Can't send game request to self");
                case 11004 ->
                        System.out.println("A game is ongoing between " + rpsStartResp.player1() + " and " + rpsStartResp.player2());
            }
        } else {
            System.out.println("Invitation sent ✔");
        }
    }

    /**
     * Initiates a Rock, Paper, Scissors game by sending a game request to the server.
     * The user is prompted to enter the opponent's username.
     * @throws IOException If an error occurs while reading user input or sending the request.
     */
    private static void initiateRpsGame() throws IOException {
        System.out.println("List of connected clients:");
        sendCommand(Commands.LIST_REQ, null);

        System.out.println("Enter your opponent: ");
        String opponent = userReader.readLine();
        sendCommand(Commands.RPS_START_REQ, new RpsStartReq(opponent));
    }

    /**
     * Handles an RPS game invitation received from the server.
     * The user can accept or decline the invitation using `/y` (yes) or `/n` (no).
     * @param jsonPayload The JSON payload containing the sender's username.
     * @throws IOException If an error occurs during JSON parsing or user input.
     */
    private static void handleRpsInvite(String jsonPayload) throws IOException {
        RpsInvite rpsInvite = mapper.readValue(jsonPayload, RpsInvite.class);

        System.out.println("You have been invited to a game by " + rpsInvite.sender());
        System.out.println("Would you like to accept?");
        System.out.println("/y - yes");
        System.out.println("/n - no");
    }

    /**
     * Processes the user's response to an RPS game invitation.
     * If the user accepts (`/y`), sends an `RPS_INVITE_RESP` command with status "ACCEPT".
     * If the user declines (`/n`), sends an `RPS_INVITE_RESP` command with status "DECLINE".
     * @param accept `true` if the user accepts the invitation, `false` otherwise.
     * @throws JsonProcessingException If the response message cannot be serialized.
     */
    private static void processRpsInvitation(boolean accept) throws JsonProcessingException {
        if (accept) {
            sendCommand(Commands.RPS_INVITE_RESP, new RpsInviteResp("ACCEPT"));
            System.out.println("Invitation accepted");
        } else {
            sendCommand(Commands.RPS_INVITE_RESP, new RpsInviteResp("DECLINE"));
            System.out.println("Invitation declined");
        }
    }

    /**
     * Sends the user's move (Rock, Paper, or Scissors) to the server during an RPS game.
     * @param move The user's move, represented as a string (`"/r"`, `"/p"`, or `"/s"`).
     * @throws JsonProcessingException If the move request cannot be serialized.
     */
    private static void sendMove(String move) throws JsonProcessingException {
        sendCommand(Commands.RPS_MOVE_REQ, new RpsMove(move));
    }

    /**
     * Handles the result of an RPS game sent by the server.
     * Flow:
     * - Parses the server's response to determine the winner and the moves played by both players.
     * - Displays the winner (or tie).
     * @param jsonPayload The JSON payload containing the game result.
     * @throws JsonProcessingException If the JSON payload cannot be parsed.
     */
    private static void handleRpsResult(String jsonPayload) throws JsonProcessingException {
        Map<String, Object> result = mapper.readValue(jsonPayload, Map.class);
        String winner = (String) result.get("winner");

        if (winner == null) {
            System.out.println("It's a tie!");
        } else {
            System.out.println("The winner is: " + winner);
        }
    }

    /**
     * Sends a command and its associated message to the server.
     *
     * @param command The command type.
     * @param message The message object to be sent (can be `null`).
     * @throws JsonProcessingException If the message cannot be serialized.
     */
    private static void sendCommand(String command, Object message) throws JsonProcessingException {
        String jsonMessage = command + " " + (message == null ? "{}" : mapper.writeValueAsString(message));
        writer.println(jsonMessage);
    }

    private static void sendPong() throws JsonProcessingException {
        sendCommand(Commands.PONG, new Pong());
    }

    private static void sendingBroadcast(String jsonPayload) throws JsonProcessingException {
        BroadcastResp broadcastResp = mapper.readValue(jsonPayload, BroadcastResp.class);
        if ((broadcastResp.status()).equals("OK")) {
            System.out.println("Sent ✔");
        } else {
            BroadcastingError(broadcastResp.code());
        }
    }

    private static void BroadcastingError(int errorCode) {
        if (errorCode == 6000) {
            System.out.println("Error: You must log in before sending a broadcast message.");
        } else {
            System.out.println("Unknown broadcast error occurred. Code: " + errorCode);
        }
    }

    private static void printMessage(String jsonPayload) throws JsonProcessingException {
        Broadcast broadcast = mapper.readValue(jsonPayload, Broadcast.class);
        System.out.println(broadcast.username() + ": " + broadcast.message());
    }

    private static void handleHangup() {
        System.out.println("Received HANGUP due to missing PONG");
        closeConnection();
    }

    private static void handleListOfConnectedClients(String jsonPayload) throws JsonProcessingException {
        ListResp listResp = mapper.readValue(jsonPayload, ListResp.class);

        if ("ERROR".equals(listResp.status())) {
            if (listResp.code() == 9000) {
                System.out.println("Cannot retrieve list: You are not logged in.");
            } else {
                System.out.println("Unknown error retrieving list: " + listResp.code());
            }
        } else {
            if (listResp.clients() != null && !listResp.clients().isEmpty()) {
                String clientListString = String.join(", ", listResp.clients());
                System.out.println("Currently connected users: " + clientListString);
            } else {
                System.out.println("(no users connected?)");
            }
        }
    }

    private static void informJoinedClient(String jsonPayload) throws JsonProcessingException {
        Joined joined = mapper.readValue(jsonPayload, Joined.class);
        System.out.println(joined.username() + " has joined the chat.");
    }

    private static void informLeftClient(String jsonPayload) throws JsonProcessingException {
        Joined left = mapper.readValue(jsonPayload, Joined.class);
        System.out.println(left.username() + " has left the chat.");
    }

    private static void printPrivateMessage(String jsonPayload) throws JsonProcessingException {
        PrivateMsg privateMsg = mapper.readValue(jsonPayload, PrivateMsg.class);
        System.out.println("[PRIVATE] " + privateMsg.sender() + ": " + privateMsg.message());
    }

    private static void privateMessageErrors(String jsonPayload) throws JsonProcessingException {
        PrivateMsgResp privateMsgResp = mapper.readValue(jsonPayload, PrivateMsgResp.class);
        if (privateMsgResp.status().equals("ERROR")) {
            switch (privateMsgResp.code()) {
                case 10001 -> System.out.println("Please log in to send private message.");
                case 10002 -> System.out.println("No receiver found.");
                case 10003 -> System.out.println("Can't send to self.");
            }
        } else {
            System.out.println("Sent ✔");
        }
    }

    private static void printHelpMenu() {
        System.out.println("Available commands:");
        System.out.println("----------------------------");
        System.out.println("/help - Show this help menu");
        System.out.println("/exit - Exit the chatroom");
        System.out.println("/all - Show all connected clients");
        System.out.println("@username <message> - Send a private message to a user");
        System.out.println("/rps - Start a Rock, Paper, Scissors game");
        System.out.println("/send <username> <file-path> - Request to send a file to another user");
        System.out.println("/files - Show all incoming file requests");
        System.out.println("/a <username> <filename> - Accept a file transfer request");
        System.out.println("/d <username> <filename> - Decline a file transfer request");
        System.out.println("Type a message to broadcast to the chatroom.");
    }

    private static void closeConnection() {
        try {
            if (writer != null) writer.close();
            if (serverReader != null) serverReader.close();
            if (socket != null && !socket.isClosed()) socket.close();
            System.out.println("All resources closed successfully.");
        } catch (IOException e) {
            System.out.println("Error while closing resources: " + e.getMessage());
        }
    }

    private static void closeFileConnection(OutputStream os, InputStream is) {
        try {
            os.close();
            is.close();
            System.out.println("File transfer sockets closed successfully.");
        } catch (IOException e) {
            System.out.println("Error while closing file transfer sockets: " + e.getMessage());
        }
    }
}
