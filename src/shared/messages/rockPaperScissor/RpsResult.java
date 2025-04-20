package shared.messages.rockPaperScissor;

import java.util.Map;

public record RpsResult(String winner, Map<String, String> playerToMove) {
}
