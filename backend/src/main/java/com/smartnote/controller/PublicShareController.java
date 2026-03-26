package com.smartnote.controller;

import com.smartnote.dto.ShareCommentRequest;
import com.smartnote.entity.Note;
import com.smartnote.entity.NoteComment;
import com.smartnote.entity.NoteShare;
import com.smartnote.repository.NoteRepository;
import com.smartnote.service.ShareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/public/shares")
public class PublicShareController {

    @Autowired
    private ShareService shareService;

    @Autowired
    private NoteRepository noteRepository;

    @GetMapping("/{token}/info")
    public ResponseEntity<?> getShareInfo(@PathVariable String token) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            boolean requireCode = share.getExtractionCode() != null && !share.getExtractionCode().isEmpty();

            Map<String, Object> response = new HashMap<>();
            response.put("requireCode", requireCode);
            if (!requireCode) {
                Note note = share.getNote();
                response.put("title", note.getTitle());
                response.put("author", note.getNotebook().getUser().getUsername());
                response.put("allowComment", share.getAllowComment());
                response.put("allowEdit", share.getAllowEdit());
            }
            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{token}")
    public ResponseEntity<?> getSharedNoteWithCode(
            @PathVariable String token,
            @RequestBody(required = false) Map<String, String> payload
    ) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            validateExtractionCode(share, payload != null ? payload.get("code") : null);

            Note note = share.getNote();
            Map<String, Object> response = new HashMap<>();
            response.put("noteId", note.getId());
            response.put("title", note.getTitle());
            response.put("content", note.getContent());
            response.put("contentHtml", note.getContentHtml());
            response.put("summary", note.getSummary());
            response.put("updatedAt", note.getUpdatedAt());
            response.put("author", note.getNotebook().getUser().getUsername());
            response.put("allowComment", share.getAllowComment());
            response.put("allowEdit", share.getAllowEdit());
            response.put("shareId", share.getId());

            return ResponseEntity.ok(response);
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PutMapping("/{token}")
    public ResponseEntity<?> updateSharedNote(@PathVariable String token, @RequestBody Map<String, Object> payload) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            if (share.getAllowEdit() == null || !share.getAllowEdit()) {
                return ResponseEntity.status(403).body(Map.of("message", "This share does not allow collaborative editing"));
            }

            validateExtractionCode(share, payload.containsKey("code") ? (String) payload.get("code") : null);

            Note note = share.getNote();
            if (payload.containsKey("content")) {
                note.setContent((String) payload.get("content"));
            }
            if (payload.containsKey("contentHtml")) {
                note.setContentHtml((String) payload.get("contentHtml"));
            }

            if (payload.containsKey("content")) {
                note.setUpdatedAt(LocalDateTime.now());
                noteRepository.save(note);
            }

            return ResponseEntity.ok(Map.of("message", "Saved", "updatedAt", note.getUpdatedAt()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/{token}/comments")
    public ResponseEntity<?> getComments(
            @PathVariable String token,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String ownerToken
    ) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            validateCommentAccess(share, code);
            return ResponseEntity.ok(shareService.getShareCommentsByShareId(share.getId(), ownerToken));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{token}/comments")
    public ResponseEntity<?> addComment(@PathVariable String token, @RequestBody ShareCommentRequest request) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            validateCommentAccess(share, request.getCode());

            NoteComment savedComment = shareService.createPublicComment(share, request);
            return ResponseEntity.ok(shareService.toCommentResponse(savedComment));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{token}/comments/{commentId}")
    public ResponseEntity<?> deleteComment(
            @PathVariable String token,
            @PathVariable Long commentId,
            @RequestParam(required = false) String code,
            @RequestParam String ownerToken
    ) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            validateCommentAccess(share, code);
            shareService.deletePublicComment(share, commentId, ownerToken);
            return ResponseEntity.ok(Map.of("message", "Comment deleted"));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    private void validateCommentAccess(NoteShare share, String code) {
        if (share.getAllowComment() == null || !share.getAllowComment()) {
            throw new IllegalStateException("This share does not allow comments");
        }

        validateExtractionCode(share, code);
    }

    private void validateExtractionCode(NoteShare share, String code) {
        String extractionCode = share.getExtractionCode();
        if (extractionCode != null && !extractionCode.isBlank()) {
            if (code == null || !extractionCode.equals(code.trim())) {
                throw new IllegalStateException("Invalid extraction code");
            }
        }
    }
}
