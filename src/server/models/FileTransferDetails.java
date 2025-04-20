package server.models;

public class FileTransferDetails {
    private String sender;
    private String receiver;
    private String filename;
    private String checksum;


    public FileTransferDetails(String sender, String receiver, String filename, String checksum) {
        this.sender = sender;
        this.receiver = receiver;
        this.filename = filename;
        this.checksum = checksum;
    }

    public String getSender() {
        return sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public String getFilename() {
        return filename;
    }

    public String getChecksum(){
        return checksum;
    }

}
