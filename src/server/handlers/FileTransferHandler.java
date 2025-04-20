package server.handlers;

import server.Server;
import server.models.TransferContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class FileTransferHandler implements Runnable {
    private static final int UUID_LENGTH = 36;
    private Socket socket;

    private Server server;

    public FileTransferHandler(Socket socket, Server server) {
        this.socket = socket;
        this.server = server;
    }

    /**
     * Handles the file transfer logic for a client connection.
     * This method coordinates the interaction between the sender and receiver clients
     * in a file transfer session, identified by a unique UUID.
     *
     * Steps:
     * 1. Reads the UUID (36 characters) and role (1 byte) from the client.
     *    - UUID: Identifies the specific file transfer session.
     *    - Role: Determines if the client is the sender ('s') or receiver ('r').
     *
     * 2. Retrieves the `TransferContext` associated with the UUID and synchronizes access.
     *    - If the client is a receiver ('r'):
     *      - Stores the receiver's output stream in the `TransferContext`.
     *    - If the client is a sender ('s'):
     *      - Stores the sender's input stream in the `TransferContext`.
     *      - Waits until the receiver's output stream is available before proceeding.
     *    - If an invalid role is provided, logs an error and closes the socket.
     *
     * 3. Transfers the file data:
     *    - Reads data from the sender's input stream and writes it to the receiver's output stream.
     *    - Closes the streams after the transfer is complete to release resources.
     *
     * 4. Handles any `IOException` or `InterruptedException` that occurs during the process.
     *
     * Error Handling:
     * - Logs errors related to socket communication or file transfer.
     * - Ensures proper resource cleanup by closing the socket in all cases.
     */
    @Override
    public void run() {
        try (InputStream senderInput = socket.getInputStream();
             OutputStream receiverOutput = socket.getOutputStream()) {
            byte[] uuid = senderInput.readNBytes(UUID_LENGTH);
            String uuidStr = new String(uuid, StandardCharsets.UTF_8);
            byte[] role = senderInput.readNBytes(1);

            TransferContext transferContext = this.server.getOngoingTransfers().get(uuidStr);

            synchronized (transferContext) {
                if (role[0] == 'r') {
                    transferContext.setReceiverOutput(receiverOutput);
                } else if (role[0] == 's') {
                    transferContext.setSenderInput(senderInput);
                    while (transferContext.getReceiverOutput() == null) {
                        transferContext.wait();
                    }
                } else {
                    System.out.println("Unknown role " + Arrays.toString(role));
                    socket.close();
                    return;
                }
            }

            if (transferContext.isAvailable()) {

                InputStream senderStream = transferContext.getSenderInput();
                OutputStream receiverStream = transferContext.getReceiverOutput();

                senderStream.transferTo(receiverStream);

                senderStream.close();
                receiverStream.close();
            }

            socket.close();
        } catch (IOException e) {
            System.out.println("File Socket error: " + e.getMessage());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}