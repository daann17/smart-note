package com.smartnote.dto;

public record AdminStorageOverviewResponse(
        long totalKnowledgeBytes,
        long totalHistoryBytes,
        long totalUploadBytes,
        long totalEstimatedBytes,
        long uploadFileCount
) {
}
