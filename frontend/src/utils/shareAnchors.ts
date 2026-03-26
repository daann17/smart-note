export type ShareAnchorType = 'heading' | 'paragraph' | 'list' | 'quote' | 'code';

export type ShareAnchor = {
  key: string;
  type: ShareAnchorType;
  label: string;
  preview: string;
  commentCount: number;
};

type DecorateShareContentOptions = {
  contentHtml?: string | null;
  activeAnchorKey?: string | null;
  commentCountByKey?: Map<string, number>;
  anchorSelector?: string;
  anchorIdPrefix?: string;
};

const DEFAULT_ANCHOR_SELECTOR = 'h1,h2,h3,h4,h5,h6,p,li,blockquote,pre';

const hashString = (value: string) => {
  let hash = 0;

  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) >>> 0;
  }

  return hash.toString(36);
};

const normalizeAnchorText = (value: string) => value.replace(/\s+/g, ' ').trim();

const getAnchorType = (tagName: string): ShareAnchorType => {
  if (/^h[1-6]$/i.test(tagName)) return 'heading';
  if (tagName === 'li') return 'list';
  if (tagName === 'blockquote') return 'quote';
  if (tagName === 'pre') return 'code';
  return 'paragraph';
};

const getAnchorLabel = (type: ShareAnchorType, preview: string) => {
  if (type === 'heading') return `\u6807\u9898\uff1a${preview}`;
  if (type === 'list') return `\u5217\u8868\u9879\uff1a${preview}`;
  if (type === 'quote') return `\u5f15\u7528\uff1a${preview}`;
  if (type === 'code') return `\u4ee3\u7801\u5757\uff1a${preview}`;
  return `\u6bb5\u843d\uff1a${preview}`;
};

export const decorateShareContent = ({
  contentHtml,
  activeAnchorKey = null,
  commentCountByKey = new Map<string, number>(),
  anchorSelector = DEFAULT_ANCHOR_SELECTOR,
  anchorIdPrefix = 'anchor',
}: DecorateShareContentOptions): { html: string; anchors: ShareAnchor[] } => {
  const sourceHtml = contentHtml?.trim();
  if (!sourceHtml || typeof DOMParser === 'undefined') {
    return {
      html: contentHtml || '<p><i>\u6682\u65e0\u5185\u5bb9</i></p>',
      anchors: [],
    };
  }

  const parser = new DOMParser();
  const doc = parser.parseFromString(`<div data-share-root="1">${sourceHtml}</div>`, 'text/html');
  const root = doc.body.firstElementChild as HTMLElement | null;

  if (!root) {
    return {
      html: sourceHtml,
      anchors: [],
    };
  }

  const occurrenceMap = new Map<string, number>();
  const anchors: ShareAnchor[] = [];

  root.querySelectorAll(anchorSelector).forEach((element) => {
    const parentAnchor = element.parentElement?.closest(anchorSelector);
    if (parentAnchor) {
      return;
    }

    const tagName = element.tagName.toLowerCase();
    const text = normalizeAnchorText(element.textContent || '');
    if (!text) {
      return;
    }

    const preview = text.slice(0, 120);
    const occurrenceSeed = `${tagName}|${preview.toLowerCase()}`;
    const occurrence = (occurrenceMap.get(occurrenceSeed) || 0) + 1;
    occurrenceMap.set(occurrenceSeed, occurrence);

    const key = `${tagName}-${hashString(`${occurrenceSeed}|${occurrence}`)}`;
    const type = getAnchorType(tagName);
    const label = getAnchorLabel(type, preview);
    const commentCount = commentCountByKey.get(key) || 0;

    element.classList.add('share-anchor-block');
    element.setAttribute('id', `${anchorIdPrefix}-${key}`);
    element.setAttribute('data-share-anchor-key', key);
    element.setAttribute('data-share-anchor-label', label);
    element.setAttribute('data-share-anchor-type', type);

    if (commentCount > 0) {
      element.classList.add('has-comments');
      element.setAttribute('data-comment-count-label', `${commentCount} \u6761\u8bc4\u8bba`);
    }

    if (activeAnchorKey === key) {
      element.classList.add('is-active');
    }

    anchors.push({
      key,
      type,
      label,
      preview,
      commentCount,
    });
  });

  return {
    html: root.innerHTML,
    anchors,
  };
};
