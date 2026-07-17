import { describe, it, expect, beforeEach, vi } from 'vitest';
import { api } from '../lib/api';

describe('api lib', () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('accessToken', 'test-token');
  });

  it('auth.getOAuthUrl calls correct endpoint', async () => {
    const mockResponse = { authUrl: 'https://accounts.google.com/...' };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify(mockResponse)),
    });

    const result = await api.auth.getOAuthUrl('google');
    expect(result.authUrl).toBe('https://accounts.google.com/...');
  });

  it('auth.callback calls correct endpoint', async () => {
    const mockResponse = {
      userId: '123',
      email: 'test@example.com',
      accessToken: 'token',
      refreshToken: 'refresh',
    };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify(mockResponse)),
    });

    const result = await api.auth.callback('google', 'code123');
    expect(result.accessToken).toBe('token');
  });

  it('emails.list calls correct endpoint', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ content: [], totalElements: 0 })),
    });

    const result = await api.emails.list(0, 20);
    expect(result.totalElements).toBe(0);
  });

  it('emails.filter builds query params', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ content: [] })),
    });

    await api.emails.filter({ isRead: 'false', fromAddress: 'boss@example.com' });
    const callUrl = global.fetch.mock.calls[0][0];
    expect(callUrl).toContain('isRead=false');
    expect(callUrl).toContain('fromAddress=boss%40example.com');
  });

  it('chat.send calls correct endpoint', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ response: 'OK', criteria: {} })),
    });

    const result = await api.chat.send('Show important emails');
    expect(result.response).toBe('OK');
  });
});
