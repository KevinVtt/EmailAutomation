import DOMPurify from 'dompurify';

const SANITIZE_CONFIG = {
  ALLOWED_TAGS: ['p', 'br', 'b', 'i', 'u', 'strong', 'em', 'a', 'ul', 'ol', 'li', 'table', 'tr', 'td', 'th', 'img', 'span', 'div'],
  ALLOWED_ATTR: ['href', 'src', 'alt', 'title'],
  ALLOW_DATA_ATTR: false,
};

export function EmailBodyIframe({ html }) {
  const clean = DOMPurify.sanitize(html || '', SANITIZE_CONFIG);
  return (
    <iframe
      title="email-body"
      sandbox="allow-same-origin"
      srcDoc={`<html><body style="margin:0;font-family:inherit">${clean}</body></html>`}
      style={{ width: '100%', border: 'none' }}
      onLoad={(e) => {
        // auto-resize según contenido
        const doc = e.target.contentDocument;
        e.target.style.height = doc.body.scrollHeight + 'px';
      }}
    />
  );
}

export default EmailBodyIframe;