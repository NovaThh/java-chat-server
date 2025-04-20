package shared.messages.fileTransfer;

public record FileTransferReady(String uuid, String type, String checksum, String filename) {
}
