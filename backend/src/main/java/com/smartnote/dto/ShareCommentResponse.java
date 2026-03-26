package com.smartnote.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class ShareCommentResponse {
    private Long id;
    private Long parentCommentId;
    private String content;
    private String authorName;
    private Boolean authorComment;
    private String anchorKey;
    private String anchorType;
    private String anchorLabel;
    private String anchorPreview;
    private Boolean resolved;
    private LocalDateTime resolvedAt;
    private String resolvedBy;
    private LocalDateTime createdAt;
    private Boolean viewerCanDelete;
    private List<ShareCommentResponse> replies;
}
