package com.smartnote.repository;

import com.smartnote.entity.NoteHistory;
import com.smartnote.repository.projection.UserOwnedCountProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NoteHistoryRepository extends JpaRepository<NoteHistory, Long> {

    List<NoteHistory> findByNoteIdOrderBySavedAtDesc(Long noteId);

    NoteHistory findFirstByNoteIdOrderBySavedAtDesc(Long noteId);

    void deleteByNoteId(Long noteId);

    void deleteBySavedAtBefore(LocalDateTime cutoffDate);

    @Query("""
        SELECT h.note.notebook.user.id AS userId, COUNT(h) AS total
        FROM NoteHistory h
        GROUP BY h.note.notebook.user.id
        """)
    List<UserOwnedCountProjection> countByUserIdGrouped();

    @Query("""
        SELECT h.note.notebook.user.id AS userId,
               COALESCE(SUM(
                   LENGTH(COALESCE(h.title, ''))
                   + LENGTH(COALESCE(h.content, ''))
                   + LENGTH(COALESCE(h.contentHtml, ''))
               ), 0) AS total
        FROM NoteHistory h
        GROUP BY h.note.notebook.user.id
        """)
    List<UserOwnedCountProjection> sumEstimatedStorageByUserIdGrouped();
}
