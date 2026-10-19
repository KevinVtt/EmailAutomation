import { describe, it, expect, afterEach } from 'vitest';
import { render } from '@testing-library/react';
import { ThemeProvider } from '../context/ThemeContext';
import EmailBodyIframe from '../components/EmailBodyIframe';

function srcDocOf(ui) {
  const { container } = render(ui);
  return container.querySelector('iframe').getAttribute('srcdoc');
}

afterEach(() => localStorage.clear());

describe('EmailBodyIframe', () => {
  it('keeps line breaks and escapes plain-text bodies', () => {
    const doc = srcDocOf(<EmailBodyIframe html={'Linea 1\nLinea 2 <https://ejemplo.com/a>'} />);
    expect(doc).toContain('white-space: pre-wrap');
    expect(doc).toContain('Linea 1\nLinea 2 &lt;https://ejemplo.com/a&gt;');
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
});
