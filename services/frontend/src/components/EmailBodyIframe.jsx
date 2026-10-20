import { useContext, useEffect, useRef } from 'react';
import DOMPurify from 'dompurify';
import { ThemeContext } from '../context/ThemeContext';

const SANITIZE_CONFIG = {
  ALLOWED_TAGS: ['p', 'br', 'b', 'i', 'u', 'strong', 'em', 'a', 'ul', 'ol', 'li', 'table', 'tr', 'td', 'th', 'img', 'span', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6'],
  ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'rel'],
  ALLOW_DATA_ATTR: false,
};

// Sin etiquetas de bloque es texto plano: se escapa y se respetan sus saltos de línea.
const HTML_TAG = /<\/?(p|div|br|span|a|img|table|tbody|tr|td|th|ul|ol|li|h[1-6]|blockquote|pre|strong|b|em|i|u|hr)\b/i;

function escapeHtml(text) {
  return text.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function shortUrl(url) {
  try {
    const { hostname, pathname, search } = new URL(url);
    const label = `${hostname}${pathname}${search ? '?…' : ''}`;
    return label.length > 64 ? `${label.slice(0, 64)}…` : label;
  } catch {
    return url.length > 64 ? `${url.slice(0, 64)}…` : url;
  }
}

// Texto plano: se acortan las URLs y quedan clicables, sin el token kilométrico a la vista.
function plainToHtml(text) {
  return text
    .replace(/<(https?:\/\/[^>\s]+)>/g, '$1')
    .split(/(https?:\/\/[^\s]+)/g)
    .map((part, i) => {
      if (i % 2 === 0) return escapeHtml(part);
      const trail = /[.,;:]+$/.exec(part)?.[0] ?? '';
      const url = trail ? part.slice(0, -trail.length) : part;
      return `<a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer" title="${escapeHtml(url)}">${escapeHtml(shortUrl(url))}</a>${escapeHtml(trail)}`;
    })
    .join('');
}

function linksInNewTab(html) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('a').forEach((a) => {
    a.setAttribute('target', '_blank');
    a.setAttribute('rel', 'noopener noreferrer');
  });
  return doc.body.innerHTML;
}

function buildDoc(html, dark) {
  const plain = !HTML_TAG.test(html || '');
  const content = plain ? plainToHtml(html || '') : linksInNewTab(DOMPurify.sanitize(html || '', SANITIZE_CONFIG));
  const color = dark ? '#e5e7eb' : '#111827';
  const link = dark ? '#93c5fd' : '#1a73e8';

  // Los estilos van dentro del iframe: el CSS de la app no cruza el documento.
  return `<html><head><style>
    html, body { margin: 0; background: transparent; overflow: hidden; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
      font-size: 15px;
      line-height: 1.6;
      color: ${color};
      overflow-wrap: anywhere;
      word-break: break-word;
      ${plain ? 'white-space: pre-wrap;' : ''}
    }
    p { margin: 0 0 12px; }
    h1, h2, h3, h4, h5, h6 { margin: 0 0 8px; font-size: 1.2em; line-height: 1.3; }
    img { max-width: 100%; height: auto; }
    table { max-width: 100%; border-collapse: collapse; }
    td, th { padding: 4px 8px; }
    a { color: ${link}; text-decoration: none; }
    a:hover { text-decoration: underline; }
    blockquote { margin: 8px 0; padding: 4px 12px; border-left: 3px solid ${dark ? '#4b5563' : '#dadce0'}; color: ${dark ? '#9ca3af' : '#5f6368'}; }
    pre, code { white-space: pre-wrap; overflow-wrap: anywhere; }
  </style></head><body>${content}</body></html>`;
}

export function EmailBodyIframe({ html }) {
  const theme = useContext(ThemeContext)?.theme;
  const ref = useRef(null);

  const fit = () => {
    const frame = ref.current;
    const body = frame?.contentDocument?.body;
    if (body) frame.style.height = `${body.scrollHeight}px`;
  };

  useEffect(() => {
    fit();
    // Las imágenes y tablas crecen después del load: se vuelve a medir.
    const body = ref.current?.contentDocument?.body;
    if (!body || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(fit);
    observer.observe(body);
    return () => observer.disconnect();
  }, [html, theme]);

  return (
    <iframe
      ref={ref}
      title="email-body"
      sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
      srcDoc={buildDoc(html, theme === 'dark')}
      style={{ width: '100%', border: 'none' }}
      onLoad={fit}
    />
  );
}

export default EmailBodyIframe;
