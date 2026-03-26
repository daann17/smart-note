package com.smartnote.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "note_comments")
@Data
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class NoteComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "share_id", nullable = false)
    private Long shareId;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "author_name", nullable = false, length = 50)
    private String authorName;

    @Column(name = "parent_comment_id")
    private Long parentCommentId;

    @Column(name = "is_author_comment")
    private Boolean authorComment = false;

    @Column(name = "owner_token", length = 64)
    private String ownerToken;

    @Column(name = "anchor_key", length = 120)
    private String anchorKey;

    @Column(name = "anchor_type", length = 20)
    private String anchorType;

    @Column(name = "anchor_label", length = 120)
    private String anchorLabel;

    @Column(name = "anchor_preview", length = 300)
    private String anchorPreview;

    @Column(name = "is_resolved")
    private Boolean resolved = false;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 50)
    private String resolvedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
