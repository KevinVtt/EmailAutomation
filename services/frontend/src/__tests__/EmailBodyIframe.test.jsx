import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import { ThemeProvider } from '../context/ThemeContext';
import EmailBodyIframe from '../components/EmailBodyIframe';

function srcDocOf(ui) {
  const { container } = render(ui);
  return container.querySelector('iframe').getAttribute('srcdoc');
}

afterEach(() => localStorage.clear());

describe('EmailBodyIframe', () => {
  it('keeps line breaks and links in plain-text bodies', () => {
    const doc = srcDocOf(<EmailBodyIframe html={'Linea 1\nLinea 2 <https://ejemplo.com/a>'} />);
    expect(doc).toContain('white-space: pre-wrap');
    expect(doc).toContain('Linea 1\nLinea 2 ');
    expect(doc).toContain('href="https://ejemplo.com/a"');
    expect(doc).toContain('>ejemplo.com/a</a>');
  });

  it('shortens long URLs but keeps the full target', () => {
    const url = 'https://store.steampowered.com/email/gift?sparams=ABC123&check=xyz';
    const doc = srcDocOf(<EmailBodyIframe html={`Ver: <${url}>`} />);
    expect(doc).toContain('>store.steampowered.com/email/gift?…</a>');
    expect(doc).toContain(`href="${url.replace('&', '&amp;')}"`);
  });

  it('opts HTML links into a new tab', () => {
    const doc = srcDocOf(<EmailBodyIframe html={'<p><a href="https://ejemplo.com">Hola</a></p>'} />);
    expect(doc).toContain('target="_blank"');
    expect(doc).toContain('rel="noopener noreferrer"');
  });

  it('keeps headings and hides the inner scrollbar', () => {
    const doc = srcDocOf(<EmailBodyIframe html={'<h2>Pedido</h2><p>Hola</p>'} />);
    expect(doc).toContain('<h2>Pedido</h2>');
    expect(doc).toContain('overflow: hidden');
  });

  it('uses light text in dark mode', () => {
    localStorage.setItem('theme', 'dark');
    const doc = srcDocOf(
      <ThemeProvider>
        <EmailBodyIframe html="<p>Hola</p>" />
      </ThemeProvider>
    );
    expect(doc).toContain('color: #e5e7eb');
  });

  it('observes the loaded document body so late-growing mails are not cut off', () => {
    const observe = vi.fn();
    const disconnect = vi.fn();
    vi.stubGlobal('ResizeObserver', vi.fn(function ResizeObserverStub() {
      return { observe, disconnect };
    }));

    const { container } = render(<EmailBodyIframe html="<p>Hola</p>" />);
    const frame = container.querySelector('iframe');
    fireEvent.load(frame);

    expect(observe).toHaveBeenCalledWith(frame.contentDocument.body);
    vi.unstubAllGlobals();
  });
});
