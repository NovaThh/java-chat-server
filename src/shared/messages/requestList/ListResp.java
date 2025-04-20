package shared.messages.requestList;

import java.util.List;

public record ListResp(String status, int code, List<String> clients) {
}
