package managerAgent.tool;

/**
 * Structured result from a remote A2A agent call.
 * Decouples link success from content quality.
 */
public class RemoteAgentResult {

    private final String agentName;
    private final boolean linkSuccess;
    private final String content;
    private final String errorMessage;
    private final int retryCount;
    private final long latencyMs;

    private RemoteAgentResult(Builder builder) {
        this.agentName = builder.agentName;
        this.linkSuccess = builder.linkSuccess;
        this.content = builder.content != null ? builder.content : "";
        this.errorMessage = builder.errorMessage != null ? builder.errorMessage : "";
        this.retryCount = builder.retryCount;
        this.latencyMs = builder.latencyMs;
    }

    public String getAgentName() { return agentName; }
    public boolean isLinkSuccess() { return linkSuccess; }
    public String getContent() { return content; }
    public String getErrorMessage() { return errorMessage; }
    public int getRetryCount() { return retryCount; }
    public long getLatencyMs() { return latencyMs; }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    public int contentLength() {
        return content != null ? content.length() : 0;
    }

    public static Builder builder(String agentName) {
        return new Builder(agentName);
    }

    public static class Builder {
        private final String agentName;
        private boolean linkSuccess;
        private String content;
        private String errorMessage;
        private int retryCount;
        private long latencyMs;

        public Builder(String agentName) {
            this.agentName = agentName;
        }

        public Builder linkSuccess(boolean linkSuccess) { this.linkSuccess = linkSuccess; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public Builder retryCount(int retryCount) { this.retryCount = retryCount; return this; }
        public Builder latencyMs(long latencyMs) { this.latencyMs = latencyMs; return this; }

        public RemoteAgentResult build() {
            return new RemoteAgentResult(this);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "RemoteAgentResult{agent=%s, linkSuccess=%s, contentLen=%d, error=%s, retries=%d, latencyMs=%d}",
                agentName, linkSuccess, contentLength(), errorMessage, retryCount, latencyMs);
    }
}
