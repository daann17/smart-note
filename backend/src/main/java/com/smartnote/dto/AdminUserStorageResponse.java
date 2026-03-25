package com.smartnote.dto;

public record AdminUserStorageResponse(
        long userId,
        String username,
        String nickname,
        long noteCount,
        long historyCount,
        long noteBytes,
        long historyBytes,
        long totalBytes
) {
}
