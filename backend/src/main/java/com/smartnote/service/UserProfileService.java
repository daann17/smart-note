package com.smartnote.service;

import com.smartnote.dto.DeleteCurrentUserRequest;
import com.smartnote.dto.UpdateUserProfileRequest;
import com.smartnote.dto.UserProfileResponse;
import com.smartnote.entity.Note;
import com.smartnote.entity.NoteFolder;
import com.smartnote.entity.NoteShare;
import com.smartnote.entity.Notebook;
import com.smartnote.entity.User;
import com.smartnote.repository.NoteCommentRepository;
import com.smartnote.repository.NoteFolderRepository;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NoteShareRepository;
import com.smartnote.repository.NotebookRepository;
import com.smartnote.repository.TagRepository;
import com.smartnote.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class UserProfileService {

    private static final String DELETE_CONFIRMATION_TEXT = "我确认注销账号";
    private static final String ROLE_ADMIN = "ADMIN";

    private final UserRepository userRepository;
    private final NotebookRepository notebookRepository;
    private final NoteRepository noteRepository;
    private final NoteFolderRepository noteFolderRepository;
    private final NoteShareRepository noteShareRepository;
    private final NoteCommentRepository noteCommentRepository;
    private final TagRepository tagRepository;
    private final NoteService noteService;
    private final RegisterEmailVerificationService registerEmailVerificationService;

    public UserProfileService(
            UserRepository userRepository,
            NotebookRepository notebookRepository,
            NoteRepository noteRepository,
            NoteFolderRepository noteFolderRepository,
            NoteShareRepository noteShareRepository,
            NoteCommentRepository noteCommentRepository,
            TagRepository tagRepository,
            NoteService noteService,
            RegisterEmailVerificationService registerEmailVerificationService
    ) {
        this.userRepository = userRepository;
        this.notebookRepository = notebookRepository;
        this.noteRepository = noteRepository;
        this.noteFolderRepository = noteFolderRepository;
        this.noteShareRepository = noteShareRepository;
        this.noteCommentRepository = noteCommentRepository;
        this.tagRepository = tagRepository;
        this.noteService = noteService;
        this.registerEmailVerificationService = registerEmailVerificationService;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUserProfile(String username) {
        return toResponse(getUserByUsername(username));
    }

    @Transactional
    public UserProfileResponse updateCurrentUserProfile(String username, UpdateUserProfileRequest request) {
        User user = getUserByUsername(username);

        user.setNickname(normalizeNickname(request.getNickname(), user.getUsername()));
        user.setBio(normalizeText(request.getBio(), 500, "简介"));
        user.setPhone(normalizePhone(request.getPhone()));
        user.setBirthday(normalizeBirthday(request.getBirthday()));

        return toResponse(userRepository.save(user));
    }

    @Transactional
    public void deleteCurrentUserAccount(String username, DeleteCurrentUserRequest request) {
        validateDeleteRequest(request);

        User user = getUserByUsername(username);
        if (isLastActiveAdmin(user)) {
            throw new IllegalArgumentException("系统至少需要保留一个启用中的管理员账号");
        }

        List<Notebook> notebooks = notebookRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        for (Notebook notebook : notebooks) {
            deleteNotebookContent(notebook.getId());
            deleteNotebookFolders(notebook.getId());
            notebookRepository.delete(notebook);
        }

        var tags = tagRepository.findByUserId(user.getId());
        if (!tags.isEmpty()) {
            tagRepository.deleteAll(tags);
        }

        userRepository.delete(user);
        registerEmailVerificationService.clearEmailState(user.getEmail());
    }

    private User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private void validateDeleteRequest(DeleteCurrentUserRequest request) {
        String confirmationText = request == null ? null : request.getConfirmationText();
        if (confirmationText == null || !DELETE_CONFIRMATION_TEXT.equals(confirmationText.trim())) {
            throw new IllegalArgumentException("请输入“我确认注销账号”后再执行注销");
        }
    }

    private boolean isLastActiveAdmin(User user) {
        return ROLE_ADMIN.equals(normalizeRole(user.getRole()))
                && user.isActive()
                && userRepository.countByRoleAndIsActiveTrue(ROLE_ADMIN) <= 1;
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "USER";
        }
        return role.trim().toUpperCase(Locale.ROOT);
    }

    private void deleteNotebookContent(Long notebookId) {
        List<Note> notes = noteRepository.findByNotebookId(notebookId);
        for (Note note : notes) {
            deleteNoteShares(note.getId());
            noteService.hardDeleteNote(note.getId());
        }
    }

    private void deleteNoteShares(Long noteId) {
        List<NoteShare> shares = noteShareRepository.findAllByNoteId(noteId);
        for (NoteShare share : shares) {
            noteCommentRepository.deleteByShareId(share.getId());
            noteShareRepository.delete(share);
        }
    }

    private void deleteNotebookFolders(Long notebookId) {
        List<NoteFolder> rootFolders = noteFolderRepository.findByNotebookIdAndParentFolderIsNullOrderBySortOrder(notebookId);
        for (NoteFolder folder : rootFolders) {
            deleteFolderRecursively(folder.getId());
        }
    }

    private void deleteFolderRecursively(Long folderId) {
        List<NoteFolder> children = noteFolderRepository.findByParentFolderId(folderId);
        for (NoteFolder child : children) {
            deleteFolderRecursively(child.getId());
        }

        noteFolderRepository.deleteById(folderId);
    }

    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getUsername(),
                user.getEmail(),
                user.getNickname(),
                user.getBio(),
                user.getPhone(),
                user.getBirthday(),
                user.getRole()
        );
    }

    private String normalizeNickname(String nickname, String fallbackUsername) {
        String normalized = normalizeText(nickname, 50, "昵称");
        return normalized == null ? fallbackUsername : normalized;
    }

    private String normalizeText(String value, int maxLength, String fieldName) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "长度不能超过" + maxLength + "个字符");
        }

        return trimmed;
    }

    private String normalizePhone(String phone) {
        String normalized = normalizeText(phone, 20, "手机号");
        if (normalized == null) {
            return null;
        }

        if (!normalized.matches("^[+0-9\\-\\s]{6,20}$")) {
            throw new IllegalArgumentException("手机号格式不正确");
        }

        return normalized;
    }

    private LocalDate normalizeBirthday(LocalDate birthday) {
        if (birthday != null && birthday.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("生日不能晚于今天");
        }

        return birthday;
    }
}
