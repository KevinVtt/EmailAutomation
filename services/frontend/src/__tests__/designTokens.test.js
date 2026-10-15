import { describe, it, expect } from 'vitest';
import config from '../../tailwind.config.js';

const { primary, accent, muted } = config.theme.extend.colors;

// WCAG 2.1 relative luminance + contrast ratio.
function luminance(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function ratio(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('design tokens', () => {
  it('maps primary to the teal scale from the design system', () => {
    expect(primary[50]).toBe('#f0fdfa');
    expect(primary[200]).toBe('#99f6e4');
    expect(primary[600]).toBe('#0d9488');
    expect(primary[900]).toBe('#134e4a');
  });

  it('maps accent to the orange scale', () => {
    expect(accent[600]).toBe('#ea580c');
  });

  it('exposes the muted surface token', () => {
    expect(muted).toBe('#e8f1f4');
  });

  it('keeps white-on-teal text at AA', () => {
    expect(ratio('#ffffff', primary[700])).toBeGreaterThanOrEqual(4.5);
    expect(ratio('#ffffff', primary[600])).toBeLessThan(4.5); // why primary-700 is the rule
    expect(ratio('#ffffff', accent[700])).toBeGreaterThanOrEqual(4.5);
    expect(ratio('#ffffff', accent[600])).toBeLessThan(4.5);
  });

  it('keeps the focus ring above the 3:1 non-text floor', () => {
    expect(ratio(primary[600], '#ffffff')).toBeGreaterThanOrEqual(3);
  });
});
