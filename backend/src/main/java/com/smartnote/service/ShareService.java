package com.smartnote.service;

import com.smartnote.dto.ShareCommentRequest;
import com.smartnote.dto.ShareCommentResponse;
import com.smartnote.entity.Note;
import com.smartnote.entity.NoteComment;
import com.smartnote.entity.NoteShare;
import com.smartnote.entity.User;
import com.smartnote.repository.NoteCommentRepository;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NoteShareRepository;
import com.smartnote.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ShareService {

    @Autowired
    private NoteShareRepository shareRepository;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NoteCommentRepository commentRepository;

    @Autowired
    private UserRepository userRepository;

    private User getCurrentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String username;
        if (principal instanceof UserDetails) {
            username = ((UserDetails) principal).getUsername();
        } else {
            username = principal.toString();
        }
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private Note getOwnedNote(Long noteId) {
        User currentUser = getCurrentUser();
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Note not found"));

        Long ownerId = note.getNotebook().getUser().getId();
        if (!ownerId.equals(currentUser.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "No permission to access this note");
        }

        return note;
    }

    @Transactional
    public NoteShare createOrUpdateShare(
            Long noteId,
            Integer expireDays,
            String extractionCode,
            Boolean allowComment,
            Boolean allowEdit
    ) {
        Note note = getOwnedNote(noteId);

        Optional<NoteShare> existingShare = shareRepository.findByNoteId(noteId);
        NoteShare share;
        if (existingShare.isPresent()) {
            share = existingShare.get();
        } else {
            share = new NoteShare();
            share.setNote(note);
            share.setToken(UUID.randomUUID().toString().replace("-", ""));
        }

        if (expireDays != null && expireDays > 0) {
            share.setExpireAt(LocalDateTime.now().plusDays(expireDays));
        } else {
            share.setExpireAt(null);
        }

        share.setExtractionCode(
                extractionCode != null && !extractionCode.trim().isEmpty() ? extractionCode.trim() : null
        );
        share.setAllowComment(allowComment != null ? allowComment : false);
        share.setAllowEdit(allowEdit != null ? allowEdit : false);
        share.setIsActive(true);

        return shareRepository.save(share);
    }

    @Transactional(readOnly = true)
    public NoteShare getShareByNoteId(Long noteId) {
        getOwnedNote(noteId);
        return shareRepository.findByNoteId(noteId).orElse(null);
    }

    @Transactional
    public void disableShare(Long noteId) {
        getOwnedNote(noteId);
        shareRepository.findByNoteId(noteId).ifPresent(share -> {
            share.setIsActive(false);
            shareRepository.save(share);
        });
    }

    @Transactional
    public void deleteShare(Long shareId) {
        User currentUser = getCurrentUser();
        NoteShare share = shareRepository.findById(shareId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Share not found"));

        Long ownerId = share.getNote().getNotebook().getUser().getId();
        if (!ownerId.equals(currentUser.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "No permission to delete this share");
        }

        commentRepository.deleteByShareId(share.getId());
        shareRepository.delete(share);
    }

    @Transactional(readOnly = true)
    public List<ShareCommentResponse> getShareCommentsByNoteId(Long noteId) {
        getOwnedNote(noteId);

        Optional<NoteShare> share = shareRepository.findByNoteId(noteId);
        if (share.isEmpty()) {
            return List.of();
        }

        return getShareCommentsByShareId(share.get().getId(), null);
    }

    @Transactional(readOnly = true)
    public List<ShareCommentResponse> getShareCommentsByShareId(Long shareId) {
        return getShareCommentsByShareId(shareId, null);
    }

    @Transactional(readOnly = true)
    public List<ShareCommentResponse> getShareCommentsByShareId(Long shareId, String viewerOwnerToken) {
        return buildCommentThreads(commentRepository.findByShareIdOrderByCreatedAtAsc(shareId), viewerOwnerToken);
    }

    @Transactional(readOnly = true)
    public NoteShare getShareByToken(String token) {
        NoteShare share = shareRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Share link does not exist"));

        if (!share.getIsActive()) {
            throw new RuntimeException("This share link has been disabled");
        }

        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("This share link has expired");
        }

        share.getNote().getNotebook().getUser().getUsername();
        return share;
    }

    @Transactional(readOnly = true)
    public List<NoteShare> getUserShares() {
        return shareRepository.findByUserId(getCurrentUser().getId());
    }

    @Transactional
    public NoteComment createPublicComment(NoteShare share, ShareCommentRequest request) {
        String authorName = normalizeOptionalText(request.getAuthorName(), 50, "Comment author", "匿名用户");
        String ownerToken = normalizeRequiredText(request.getOwnerToken(), 64, "Comment owner token");
        return createComment(share, request, authorName, false, ownerToken);
    }

    @Transactional
    public NoteComment createOwnerComment(Long noteId, ShareCommentRequest request) {
        User currentUser = getCurrentUser();
        Note note = getOwnedNote(noteId);
        NoteShare share = shareRepository.findByNoteId(note.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Share not found"));

        if (!Boolean.TRUE.equals(share.getAllowComment())) {
            throw new ResponseStatusException(FORBIDDEN, "Share comments are disabled");
        }

        return createComment(share, request, resolveDisplayName(currentUser), true, null);
    }

    @Transactional
    public NoteComment updateCommentResolvedStatus(Long noteId, Long commentId, boolean resolved) {
        User currentUser = getCurrentUser();
        Note note = getOwnedNote(noteId);
        NoteShare share = shareRepository.findByNoteId(note.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Share not found"));

        NoteComment comment = commentRepository.findByIdAndShareId(commentId, share.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Comment not found"));

        if (comment.getParentCommentId() != null) {
            comment = commentRepository.findByIdAndShareId(comment.getParentCommentId(), share.getId())
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Parent comment not found"));
        }

        comment.setResolved(resolved);
        if (resolved) {
            comment.setResolvedAt(LocalDateTime.now());
            comment.setResolvedBy(resolveDisplayName(currentUser));
        } else {
            comment.setResolvedAt(null);
            comment.setResolvedBy(null);
        }

        return commentRepository.save(comment);
    }

    @Transactional
    public void deleteShareComment(Long noteId, Long commentId) {
        Note note = getOwnedNote(noteId);
        NoteShare share = shareRepository.findByNoteId(note.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Share not found"));

        NoteComment comment = commentRepository.findByIdAndShareId(commentId, share.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Comment not found"));

        if (comment.getParentCommentId() == null) {
            List<NoteComment> replies = commentRepository.findByShareIdAndParentCommentId(share.getId(), comment.getId());
            if (!replies.isEmpty()) {
                commentRepository.deleteAll(replies);
            }
        }

        commentRepository.delete(comment);
    }

    @Transactional
    public void deletePublicComment(NoteShare share, Long commentId, String ownerToken) {
        String normalizedOwnerToken = normalizeRequiredText(ownerToken, 64, "Comment owner token");
        NoteComment comment = commentRepository.findByIdAndShareId(commentId, share.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Comment not found"));

        if (!ownsComment(comment, normalizedOwnerToken)) {
            throw new ResponseStatusException(FORBIDDEN, "No permission to delete this comment");
        }

        if (comment.getParentCommentId() == null) {
            List<NoteComment> replies = commentRepository.findByShareIdAndParentCommentId(share.getId(), comment.getId());
            if (!replies.isEmpty()) {
                boolean canDeleteThread = replies.stream().allMatch(reply -> ownsComment(reply, normalizedOwnerToken));
                if (!canDeleteThread) {
                    throw new IllegalStateException("This comment already has replies from other users and cannot be deleted");
                }
                commentRepository.deleteAll(replies);
            }
        }

        commentRepository.delete(comment);
    }

    public ShareCommentResponse toCommentResponse(NoteComment comment) {
        return toCommentResponse(comment, false);
    }

    public ShareCommentResponse toCommentResponse(NoteComment comment, boolean viewerCanDelete) {
        return new ShareCommentResponse(
                comment.getId(),
                comment.getParentCommentId(),
                comment.getContent(),
                comment.getAuthorName(),
                Boolean.TRUE.equals(comment.getAuthorComment()),
                comment.getAnchorKey(),
                comment.getAnchorType(),
                comment.getAnchorLabel(),
                comment.getAnchorPreview(),
                Boolean.TRUE.equals(comment.getResolved()),
                comment.getResolvedAt(),
                comment.getResolvedBy(),
                comment.getCreatedAt(),
                viewerCanDelete,
                new ArrayList<>()
        );
    }

    private String resolveDisplayName(User user) {
        if (user.getNickname() != null && !user.getNickname().trim().isEmpty()) {
            return user.getNickname().trim();
        }
        return user.getUsername();
    }

    private NoteComment createComment(
            NoteShare share,
            ShareCommentRequest request,
            String authorName,
            boolean authorComment,
            String ownerToken
    ) {
        NoteComment parentComment = null;
        if (request.getParentCommentId() != null) {
            parentComment = commentRepository.findByIdAndShareId(request.getParentCommentId(), share.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Reply target not found"));

            if (parentComment.getParentCommentId() != null) {
                throw new IllegalArgumentException("Only one-level replies are supported");
            }
        }

        NoteComment comment = new NoteComment();
        comment.setShareId(share.getId());
        comment.setParentCommentId(parentComment != null ? parentComment.getId() : null);
        comment.setContent(normalizeRequiredText(request.getContent(), 1000, "Comment content"));
        comment.setAuthorName(authorName);
        comment.setAuthorComment(authorComment);
        comment.setOwnerToken(ownerToken);

        if (parentComment != null) {
            comment.setAnchorKey(parentComment.getAnchorKey());
            comment.setAnchorType(parentComment.getAnchorType());
            comment.setAnchorLabel(parentComment.getAnchorLabel());
            comment.setAnchorPreview(parentComment.getAnchorPreview());
            reopenThreadIfNeeded(parentComment);
        } else {
            comment.setAnchorKey(normalizeOptionalText(request.getAnchorKey(), 120, "Anchor key", null));
            comment.setAnchorType(normalizeOptionalText(request.getAnchorType(), 20, "Anchor type", null));
            comment.setAnchorLabel(normalizeOptionalText(request.getAnchorLabel(), 120, "Anchor label", null));
            comment.setAnchorPreview(normalizeOptionalText(request.getAnchorPreview(), 300, "Anchor preview", null));
        }

        comment.setResolved(false);
        comment.setResolvedAt(null);
        comment.setResolvedBy(null);
        return commentRepository.save(comment);
    }

    private void reopenThreadIfNeeded(NoteComment parentComment) {
        if (!Boolean.TRUE.equals(parentComment.getResolved())) {
            return;
        }

        parentComment.setResolved(false);
        parentComment.setResolvedAt(null);
        parentComment.setResolvedBy(null);
        commentRepository.save(parentComment);
    }

    private List<ShareCommentResponse> buildCommentThreads(List<NoteComment> comments, String viewerOwnerToken) {
        Map<Long, ShareCommentResponse> commentMap = new LinkedHashMap<>();
        for (NoteComment comment : comments) {
            commentMap.put(comment.getId(), toCommentResponse(comment, ownsComment(comment, viewerOwnerToken)));
        }

        List<ShareCommentResponse> roots = new ArrayList<>();
        for (NoteComment comment : comments) {
            ShareCommentResponse response = commentMap.get(comment.getId());
            Long parentCommentId = comment.getParentCommentId();

            if (parentCommentId != null && commentMap.containsKey(parentCommentId)) {
                commentMap.get(parentCommentId).getReplies().add(response);
            } else {
                roots.add(response);
            }
        }

        roots.sort((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()));
        return roots;
    }

    private boolean ownsComment(NoteComment comment, String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            return false;
        }

        return !Boolean.TRUE.equals(comment.getAuthorComment()) && ownerToken.equals(comment.getOwnerToken());
    }

    private String normalizeRequiredText(String value, int maxLength, String fieldName) {
        String normalized = normalizeOptionalText(value, maxLength, fieldName, null);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, int maxLength, String fieldName, String fallback) {
        if (value == null) {
            return fallback;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }

        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " length must be <= " + maxLength);
        }

        return trimmed;
    }
}
