package shared;

public class Commands {
    public static final String READY = "READY";
    public static final String ENTER = "ENTER";
    public static final String ENTER_RESP = "ENTER_RESP";
    public static final String BROADCAST_REQ = "BROADCAST_REQ";
    public static final String BROADCAST_RESP = "BROADCAST_RESP";
    public static final String BROADCAST = "BROADCAST";
    public static final String JOINED = "JOINED";
    public static final String LEFT = "LEFT";
    public static final String BYE = "BYE";
    public static final String BYE_RESP = "BYE_RESP";
    public static final String UNKNOWN_COMMAND = "UNKNOWN_COMMAND";
    public static final String PING = "PING";
    public static final String PONG = "PONG";
    public static final String LIST_REQ = "LIST_REQ";
    public static final String LIST_RESP = "LIST_RESP";
    public static final String PRIVATE_MSG = "PRIVATE_MSG";
    public static final String HANGUP = "HANGUP";
    public static final String PRIVATE_MSG_REQ = "PRIVATE_MSG_REQ";
    public static final String PRIVATE_MSG_RESP = "PRIVATE_MSG_RESP";
    public static final String PONG_ERROR = "PONG_ERROR";
    public static final String PARSE_ERROR = "PARSE_ERROR";
    public static final String RPS_START_REQ = "RPS_START_REQ";
    public static final String RPS_START_RESP = "RPS_START_RESP";
    public static final String RPS_INVITE = "RPS_INVITE";
    public static final String RPS_READY = "RPS_READY";
    public static final String RPS_INVITE_DECLINED = "RPS_INVITE_DECLINED";
    public static final String RPS_INVITE_RESP = "RPS_INVITE_RESP";
    public static final String RPS_MOVE_RESP = "RPS_MOVE_RESP";
    public static final String RPS_RESULT = "RPS_RESULT";
    public static final String RPS_MOVE_REQ = "RPS_MOVE_REQ";
    public static final String FILE_TRANSFER_REQ = "FILE_TRANSFER_REQ";
    public static final String FILE_TRANSFER_RESP = "FILE_TRANSFER_RESP" ;
    public static final String FILE_TRANSFER_READY = "FILE_TRANSFER_READY";
}
