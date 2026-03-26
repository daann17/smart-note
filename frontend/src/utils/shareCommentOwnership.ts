const OWNER_TOKEN_PREFIX = 'smartnote:share-comment-owner:';

const createOwnerToken = () => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '');
  }

  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 18)}`;
};

export const getShareCommentOwnerToken = (shareToken: string) => {
  if (typeof localStorage === 'undefined') {
    return '';
  }

  return localStorage.getItem(`${OWNER_TOKEN_PREFIX}${shareToken}`) || '';
};

export const getOrCreateShareCommentOwnerToken = (shareToken: string) => {
  const storedToken = getShareCommentOwnerToken(shareToken);
  if (storedToken) {
    return storedToken;
  }

  const ownerToken = createOwnerToken();
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(`${OWNER_TOKEN_PREFIX}${shareToken}`, ownerToken);
  }
  return ownerToken;
};
