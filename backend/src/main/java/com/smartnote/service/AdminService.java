package com.smartnote.service;

import com.smartnote.dto.AdminOverviewResponse;
import com.smartnote.dto.AdminStorageOverviewResponse;
import com.smartnote.dto.AdminUserStorageResponse;
import com.smartnote.dto.AdminUserSummaryResponse;
import com.smartnote.dto.UpdateAdminUserRoleRequest;
import com.smartnote.dto.UpdateAdminUserStatusRequest;
import com.smartnote.entity.User;
import com.smartnote.repository.NoteHistoryRepository;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NotebookRepository;
import com.smartnote.repository.TagRepository;
import com.smartnote.repository.UserRepository;
import com.smartnote.repository.projection.UserOwnedCountProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";
    private static final String STATUS_ALL = "ALL";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String TRASH_STATUS = "TRASH";
    private static final Sort USER_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final UserRepository userRepository;
    private final NoteRepository noteRepository;
    private final NotebookRepository notebookRepository;
    private final TagRepository tagRepository;
    private final NoteHistoryRepository noteHistoryRepository;
    private final Path uploadDirectory;

    public AdminService(
            UserRepository userRepository,
            NoteRepository noteRepository,
            NotebookRepository notebookRepository,
            TagRepository tagRepository,
            NoteHistoryRepository noteHistoryRepository,
            @Value("${file.upload-dir:./uploads}") String uploadDir
    ) {
        this.userRepository = userRepository;
        this.noteRepository = noteRepository;
        this.notebookRepository = notebookRepository;
        this.tagRepository = tagRepository;
        this.noteHistoryRepository = noteHistoryRepository;
        this.uploadDirectory = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public AdminOverviewResponse getOverview() {
        return new AdminOverviewResponse(
                userRepository.count(),
                userRepository.countByIsActiveTrue(),
                userRepository.countByIsActiveFalse(),
                userRepository.countByRole(ROLE_ADMIN),
                noteRepository.countByStatusNot(TRASH_STATUS),
                notebookRepository.countByStatusNot(TRASH_STATUS),
                tagRepository.count()
        );
    }

    @Transactional(readOnly = true)
    public AdminStorageOverviewResponse getStorageOverview() {
        Map<Long, Long> noteBytes = toCountMap(noteRepository.sumEstimatedStorageByUserIdGroupedExcludingStatus(TRASH_STATUS));
        Map<Long, Long> historyBytes = toCountMap(noteHistoryRepository.sumEstimatedStorageByUserIdGrouped());
        UploadDirectoryStats uploadStats = readUploadDirectoryStats();

        long totalKnowledgeBytes = sumMapValues(noteBytes);
        long totalHistoryBytes = sumMapValues(historyBytes);
        long totalEstimatedBytes = totalKnowledgeBytes + totalHistoryBytes + uploadStats.totalBytes();

        return new AdminStorageOverviewResponse(
                totalKnowledgeBytes,
                totalHistoryBytes,
                uploadStats.totalBytes(),
                totalEstimatedBytes,
                uploadStats.fileCount()
        );
    }

    @Transactional(readOnly = true)
    public List<AdminUserSummaryResponse> listUsers(String keyword, String status, String role) {
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedStatus = normalizeStatus(status);
        String normalizedRole = normalizeRole(role);

        Map<Long, Long> noteCounts = toCountMap(noteRepository.countByUserIdGroupedExcludingStatus(TRASH_STATUS));
        Map<Long, Long> notebookCounts = toCountMap(notebookRepository.countByUserIdGroupedExcludingStatus(TRASH_STATUS));
        Map<Long, Long> tagCounts = toCountMap(tagRepository.countByUserIdGrouped());

        return userRepository.findAll(USER_SORT).stream()
                .filter((user) -> matchesKeyword(user, normalizedKeyword))
                .filter((user) -> matchesStatus(user, normalizedStatus))
                .filter((user) -> matchesRole(user, normalizedRole))
                .map((user) -> toUserSummaryResponse(user, noteCounts, notebookCounts, tagCounts))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminUserStorageResponse> listUserStorage(String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);

        Map<Long, Long> noteCounts = toCountMap(noteRepository.countByUserIdGroupedExcludingStatus(TRASH_STATUS));
        Map<Long, Long> historyCounts = toCountMap(noteHistoryRepository.countByUserIdGrouped());
        Map<Long, Long> noteBytes = toCountMap(noteRepository.sumEstimatedStorageByUserIdGroupedExcludingStatus(TRASH_STATUS));
        Map<Long, Long> historyBytes = toCountMap(noteHistoryRepository.sumEstimatedStorageByUserIdGrouped());

        return userRepository.findAll(USER_SORT).stream()
                .filter((user) -> matchesKeyword(user, normalizedKeyword))
                .map((user) -> toUserStorageResponse(user, noteCounts, historyCounts, noteBytes, historyBytes))
                .sorted((left, right) -> {
                    int totalCompare = Long.compare(right.totalBytes(), left.totalBytes());
                    if (totalCompare != 0) {
                        return totalCompare;
                    }
                    return left.username().compareToIgnoreCase(right.username());
                })
                .toList();
    }

    @Transactional
    public AdminUserSummaryResponse updateUserStatus(Long userId, UpdateAdminUserStatusRequest request, String actorUsername) {
        if (request.getActive() == null) {
            throw new IllegalArgumentException("User active status must not be null");
        }

        User actor = getUserByUsername(actorUsername);
        User target = getUserById(userId);

        if (!request.getActive()) {
            if (Objects.equals(actor.getId(), target.getId())) {
                throw new IllegalArgumentException("You cannot disable the currently logged in admin");
            }

            if (isAdmin(target) && target.isActive() && userRepository.countByRoleAndIsActiveTrue(ROLE_ADMIN) <= 1) {
                throw new IllegalArgumentException("At least one active admin must remain in the system");
            }
        }

        target.setActive(request.getActive());
        return toUserSummaryResponse(userRepository.save(target));
    }

    @Transactional
    public AdminUserSummaryResponse updateUserRole(Long userId, UpdateAdminUserRoleRequest request, String actorUsername) {
        String nextRole = normalizeManagedRole(request.getRole());
        User actor = getUserByUsername(actorUsername);
        User target = getUserById(userId);
        String currentRole = normalizeStoredRole(target.getRole());

        if (Objects.equals(actor.getId(), target.getId()) && ROLE_USER.equals(nextRole)) {
            throw new IllegalArgumentException("You cannot downgrade the currently logged in admin");
        }

        if (ROLE_ADMIN.equals(currentRole) && ROLE_USER.equals(nextRole) && target.isActive()
                && userRepository.countByRoleAndIsActiveTrue(ROLE_ADMIN) <= 1) {
            throw new IllegalArgumentException("At least one active admin must remain in the system");
        }

        target.setRole(nextRole);
        return toUserSummaryResponse(userRepository.save(target));
    }

    private Map<Long, Long> toCountMap(List<UserOwnedCountProjection> projections) {
        return projections.stream().collect(Collectors.toMap(
                UserOwnedCountProjection::getUserId,
                UserOwnedCountProjection::getTotal
        ));
    }

    private long sumMapValues(Map<Long, Long> values) {
        return values.values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    private AdminUserSummaryResponse toUserSummaryResponse(
            User user,
            Map<Long, Long> noteCounts,
            Map<Long, Long> notebookCounts,
            Map<Long, Long> tagCounts
    ) {
        long userId = user.getId();
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getNickname(),
                normalizeStoredRole(user.getRole()),
                user.isActive(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                noteCounts.getOrDefault(userId, 0L),
                notebookCounts.getOrDefault(userId, 0L),
                tagCounts.getOrDefault(userId, 0L)
        );
    }

    private AdminUserSummaryResponse toUserSummaryResponse(User user) {
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getNickname(),
                normalizeStoredRole(user.getRole()),
                user.isActive(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                noteRepository.countByNotebookUserIdAndStatusNot(user.getId(), TRASH_STATUS),
                notebookRepository.countByUserIdAndStatusNot(user.getId(), TRASH_STATUS),
                tagRepository.countByUserId(user.getId())
        );
    }

    private AdminUserStorageResponse toUserStorageResponse(
            User user,
            Map<Long, Long> noteCounts,
            Map<Long, Long> historyCounts,
            Map<Long, Long> noteBytes,
            Map<Long, Long> historyBytes
    ) {
        long userId = user.getId();
        long userNoteBytes = noteBytes.getOrDefault(userId, 0L);
        long userHistoryBytes = historyBytes.getOrDefault(userId, 0L);

        return new AdminUserStorageResponse(
                userId,
                user.getUsername(),
                user.getNickname(),
                noteCounts.getOrDefault(userId, 0L),
                historyCounts.getOrDefault(userId, 0L),
                userNoteBytes,
                userHistoryBytes,
                userNoteBytes + userHistoryBytes
        );
    }

    private boolean matchesKeyword(User user, String keyword) {
        if (keyword == null) {
            return true;
        }

        return Stream.of(user.getUsername(), user.getEmail(), user.getNickname())
                .filter(Objects::nonNull)
                .map((value) -> value.toLowerCase(Locale.ROOT))
                .anyMatch((value) -> value.contains(keyword));
    }

    private boolean matchesStatus(User user, String status) {
        return switch (status) {
            case STATUS_ACTIVE -> user.isActive();
            case STATUS_INACTIVE -> !user.isActive();
            default -> true;
        };
    }

    private boolean matchesRole(User user, String role) {
        if (STATUS_ALL.equals(role)) {
            return true;
        }

        return normalizeStoredRole(user.getRole()).equals(role);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }

        return keyword.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return STATUS_ALL;
        }

        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_ALL, STATUS_ACTIVE, STATUS_INACTIVE).contains(normalized)) {
            throw new IllegalArgumentException("Unsupported user status filter");
        }

        return normalized;
    }

    private String normalizeRole(String role) {
        if (role == null || role.trim().isEmpty()) {
            return STATUS_ALL;
        }

        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_ALL, ROLE_USER, ROLE_ADMIN).contains(normalized)) {
            throw new IllegalArgumentException("Unsupported role filter");
        }

        return normalized;
    }

    private String normalizeManagedRole(String role) {
        String normalized = normalizeRole(role);
        if (STATUS_ALL.equals(normalized)) {
            throw new IllegalArgumentException("Managed role must not be empty");
        }
        return normalized;
    }

    private String normalizeStoredRole(String role) {
        if (role == null || role.isBlank()) {
            return ROLE_USER;
        }

        return role.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isAdmin(User user) {
        return ROLE_ADMIN.equals(normalizeStoredRole(user.getRole()));
    }

    private UploadDirectoryStats readUploadDirectoryStats() {
        if (!Files.exists(uploadDirectory)) {
            return new UploadDirectoryStats(0L, 0L);
        }

        long[] stats = new long[2];
        try (Stream<Path> stream = Files.walk(uploadDirectory)) {
            stream.filter(Files::isRegularFile).forEach((path) -> {
                stats[0] += 1;
                try {
                    stats[1] += Files.size(path);
                } catch (IOException exception) {
                    log.warn("Failed to read upload file size for {}", path, exception);
                }
            });
        } catch (IOException exception) {
            log.warn("Failed to scan upload directory {}", uploadDirectory, exception);
        }

        return new UploadDirectoryStats(stats[0], stats[1]);
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Admin account not found"));
    }

    private record UploadDirectoryStats(long fileCount, long totalBytes) {
    }
}
