import { useContext, useEffect, useRef } from 'react';
import DOMPurify from 'dompurify';
import { ThemeContext } from '../context/ThemeContext';

const SANITIZE_CONFIG = {
  ALLOWED_TAGS: ['p', 'br', 'b', 'i', 'u', 'strong', 'em', 'a', 'ul', 'ol', 'li', 'table', 'tr', 'td', 'th', 'img', 'span', 'div'],
  ALLOWED_ATTR: ['href', 'src', 'alt', 'title'],
  ALLOW_DATA_ATTR: false,
};

// Sin etiquetas de bloque es texto plano: se escapa y se respetan sus saltos de línea.
const HTML_TAG = /<\/?(p|div|br|span|a|img|table|tbody|tr|td|th|ul|ol|li|h[1-6]|blockquote|pre|strong|b|em|i|u|hr)\b/i;

function escapeHtml(text) {
  return text.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function buildDoc(html, dark) {
  const plain = !HTML_TAG.test(html || '');
  const content = plain ? escapeHtml(html || '') : DOMPurify.sanitize(html || '', SANITIZE_CONFIG);
  const color = dark ? '#e5e7eb' : '#111827';
  const link = dark ? '#93c5fd' : '#1a73e8';

  // Los estilos van dentro del iframe: el CSS de la app no cruza el documento.
  return `<html><head><style>
    html, body { margin: 0; background: transparent; }
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
      sandbox="allow-same-origin"
      srcDoc={buildDoc(html, theme === 'dark')}
      style={{ width: '100%', border: 'none' }}
      onLoad={fit}
    />
  );
}

export default EmailBodyIframe;
