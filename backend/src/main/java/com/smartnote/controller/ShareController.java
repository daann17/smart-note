package com.smartnote.controller;

import com.smartnote.dto.ShareCommentRequest;
import com.smartnote.dto.ShareCommentResolveRequest;
import com.smartnote.entity.NoteComment;
import com.smartnote.entity.NoteShare;
import com.smartnote.service.ShareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shares")
public class ShareController {

    @Autowired
    private ShareService shareService;

    @GetMapping
    public ResponseEntity<?> getUserShares() {
        List<NoteShare> shares = shareService.getUserShares();
        return ResponseEntity.ok(shares);
    }

    @PostMapping("/note/{noteId}")
    public ResponseEntity<?> createShare(@PathVariable Long noteId, @RequestBody(required = false) Map<String, Object> payload) {
        Integer expireDays = null;
        String extractionCode = null;
        Boolean allowComment = false;
        Boolean allowEdit = false;
        if (payload != null) {
            if (payload.containsKey("expireDays") && payload.get("expireDays") != null) {
                Object expireDaysValue = payload.get("expireDays");
                if (expireDaysValue instanceof Integer) {
                    expireDays = (Integer) expireDaysValue;
                } else if (expireDaysValue instanceof String) {
                    try {
                        expireDays = Integer.parseInt((String) expireDaysValue);
                    } catch (NumberFormatException ignored) {
                        // Ignore invalid expireDays and fall back to permanent share.
                    }
                }
            }
            if (payload.containsKey("extractionCode")) {
                extractionCode = (String) payload.get("extractionCode");
            }
            if (payload.containsKey("allowComment")) {
                Object allowCommentValue = payload.get("allowComment");
                if (allowCommentValue instanceof Boolean) {
                    allowComment = (Boolean) allowCommentValue;
                } else if (allowCommentValue instanceof String) {
                    allowComment = Boolean.parseBoolean((String) allowCommentValue);
                }
            }
            if (payload.containsKey("allowEdit")) {
                Object allowEditValue = payload.get("allowEdit");
                if (allowEditValue instanceof Boolean) {
                    allowEdit = (Boolean) allowEditValue;
                } else if (allowEditValue instanceof String) {
                    allowEdit = Boolean.parseBoolean((String) allowEditValue);
                }
            }
        }
        NoteShare share = shareService.createOrUpdateShare(noteId, expireDays, extractionCode, allowComment, allowEdit);
        return ResponseEntity.ok(share);
    }

    @GetMapping("/note/{noteId}")
    public ResponseEntity<?> getShare(@PathVariable Long noteId) {
        NoteShare share = shareService.getShareByNoteId(noteId);
        if (share == null) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.ok(share);
    }

    @GetMapping("/note/{noteId}/comments")
    public ResponseEntity<?> getShareComments(@PathVariable Long noteId) {
        return ResponseEntity.ok(shareService.getShareCommentsByNoteId(noteId));
    }

    @PostMapping("/note/{noteId}/comments")
    public ResponseEntity<?> createOwnerComment(
            @PathVariable Long noteId,
            @RequestBody ShareCommentRequest request
    ) {
        NoteComment comment = shareService.createOwnerComment(noteId, request);
        return ResponseEntity.ok(shareService.toCommentResponse(comment));
    }

    @PutMapping("/note/{noteId}/comments/{commentId}/resolve")
    public ResponseEntity<?> updateCommentResolvedStatus(
            @PathVariable Long noteId,
            @PathVariable Long commentId,
            @RequestBody(required = false) ShareCommentResolveRequest request
    ) {
        boolean resolved = request != null && Boolean.TRUE.equals(request.getResolved());
        NoteComment comment = shareService.updateCommentResolvedStatus(noteId, commentId, resolved);
        return ResponseEntity.ok(shareService.toCommentResponse(comment));
    }

    @DeleteMapping("/note/{noteId}/comments/{commentId}")
    public ResponseEntity<?> deleteShareComment(
            @PathVariable Long noteId,
            @PathVariable Long commentId
    ) {
        shareService.deleteShareComment(noteId, commentId);
        return ResponseEntity.ok(Map.of("message", "Comment deleted"));
    }

    @DeleteMapping("/note/{noteId}")
    public ResponseEntity<?> disableShare(@PathVariable Long noteId) {
        shareService.disableShare(noteId);
        return ResponseEntity.ok(Map.of("message", "Share disabled"));
    }
}
