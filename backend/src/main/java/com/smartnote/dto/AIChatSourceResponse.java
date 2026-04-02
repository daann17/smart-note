package com.smartnote.dto;

public record AIChatSourceResponse(
        Long noteId,
        Long notebookId,
        String title,
        String snippet,
        String updatedAt,
        String kind
) {
}
