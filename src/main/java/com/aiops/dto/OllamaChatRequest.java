package com.aiops.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OllamaChatRequest {
    private String model;
    private List<Message> messages;
    private boolean stream = false;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Message {
        private String role;   // system | user | assistant
        private String content;
    }
}
