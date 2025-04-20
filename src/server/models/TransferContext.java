package server.models;

import java.io.InputStream;
import java.io.OutputStream;

public class TransferContext {
    public InputStream senderInput;
    public OutputStream receiverOutput;

    public void setSenderInput(InputStream senderInput) {
        this.senderInput = senderInput;
    }

    public void setReceiverOutput(OutputStream receiverOutput) {
        this.receiverOutput = receiverOutput;
    }

    public synchronized InputStream getSenderInput() throws InterruptedException {
        while (senderInput == null) {
            wait();
        }
        return senderInput;
    }

    public synchronized OutputStream getReceiverOutput() throws InterruptedException {
        while (receiverOutput == null) {
            wait();
        }
        return receiverOutput;
    }

    public boolean isAvailable() {
        return senderInput != null && receiverOutput != null;
    }
}
