package com.aiops.dto;

import lombok.Data;

@Data
public class OllamaChatResponse {
    private Message message;
    private boolean done;

    @Data
    public static class Message {
        private String role;
        private String content;
    }
}
