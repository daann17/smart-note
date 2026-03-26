import type { ShareComment } from '../stores/note';

export const countShareCommentMessages = (comments: ShareComment[]): number => (
  comments.reduce((total, comment) => total + 1 + countShareCommentMessages(comment.replies || []), 0)
);

export const collectShareAnchorCommentCounts = (comments: ShareComment[]) => {
  const counts = new Map<string, number>();

  for (const comment of comments) {
    if (!comment.anchorKey) {
      continue;
    }

    counts.set(comment.anchorKey, (counts.get(comment.anchorKey) || 0) + 1);
  }

  return counts;
};
