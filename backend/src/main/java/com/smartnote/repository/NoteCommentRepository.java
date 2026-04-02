package com.smartnote.repository;

import com.smartnote.entity.NoteComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NoteCommentRepository extends JpaRepository<NoteComment, Long> {
    List<NoteComment> findByShareIdOrderByCreatedAtAsc(Long shareId);
    List<NoteComment> findByShareIdOrderByCreatedAtDesc(Long shareId);
    List<NoteComment> findByShareIdAndParentCommentId(Long shareId, Long parentCommentId);
    Optional<NoteComment> findByIdAndShareId(Long id, Long shareId);
    long deleteByShareId(Long shareId);
}
