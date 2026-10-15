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
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify(mockResponse)),
    });

    const result = await api.auth.getOAuthUrl('google');
    expect(result.authUrl).toBe('https://accounts.google.com/...');
  });

  it('auth.callback sends authorizationCode, provider and state', async () => {
    const mockResponse = {
      userId: '123',
      email: 'test@example.com',
      accessToken: 'token',
      refreshToken: 'refresh',
    };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify(mockResponse)),
    });

    const result = await api.auth.callback('google', 'code123', 'csrf-state-9');
    const [callUrl, callOptions] = global.fetch.mock.calls[0];
    expect(callUrl).toContain('/api/auth/callback/google');
    expect(JSON.parse(callOptions.body)).toEqual({
      authorizationCode: 'code123',
      provider: 'google',
      state: 'csrf-state-9',
    });
    expect(result.accessToken).toBe('token');
  });

  it('emails.list calls correct endpoint', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify({ content: [], totalElements: 0 })),
    });

    const result = await api.emails.list(0, 20);
    expect(result.totalElements).toBe(0);
  });

  it('emails.filter builds query params', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
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
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify({ response: 'OK', criteria: {} })),
    });

    const result = await api.chat.send('Show important emails');
    expect(result.response).toBe('OK');
  });

  it('emails.stats calls the single stats endpoint and returns parsed body', async () => {
    const mockResponse = {
      total: 120,
      unread: 30,
      starred: 12,
      spam: 8,
      perDay: [
        { date: '2026-10-09', count: 4 },
        { date: '2026-10-10', count: 6 },
      ],
    };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify(mockResponse)),
    });

    const result = await api.emails.stats();
    const callUrl = global.fetch.mock.calls[0][0];
    expect(callUrl).toContain('/api/emails/stats');
    expect(result).toEqual(mockResponse);
  });

  it('emails.summary calls correct endpoint and returns parsed body', async () => {
    const mockResponse = {
      summary: 'Hoy recibiste 3 correos importantes.',
      emailCount: 3,
      date: '2026-09-09',
    };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify(mockResponse)),
    });

    const result = await api.emails.summary('today');
    const callUrl = global.fetch.mock.calls[0][0];
    expect(callUrl).toContain('/api/emails/summary?date=today');
    expect(result).toEqual(mockResponse);
  });

  it('emails.prioritySummary calls correct endpoint and returns parsed body', async () => {
    const mockResponse = { urgentes: 3, destacadosSinLeer: 12, pendientesRespuesta: 5 };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify(mockResponse)),
    });

    const result = await api.emails.prioritySummary();
    const callUrl = global.fetch.mock.calls[0][0];
    expect(callUrl).toContain('/api/emails/priority-summary');
    expect(result).toEqual(mockResponse);
  });

  it('emails.send POSTs to the send endpoint with the correct body', async () => {
    const mockResponse = { status: 'sent', messageId: 'msg-123' };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify(mockResponse)),
    });

    const result = await api.emails.send('google', 'provider-email-1', 'Hola, gracias por tu mensaje.');
    const [callUrl, callOptions] = global.fetch.mock.calls[0];
    expect(callUrl).toContain('/api/emails/send');
    expect(callOptions.method).toBe('POST');
    expect(JSON.parse(callOptions.body)).toEqual({
      provider: 'google',
      emailId: 'provider-email-1',
      body: 'Hola, gracias por tu mensaje.',
    });
    expect(result).toEqual(mockResponse);
  });

  it('clears auth and navigates via SPA when refresh fails on 401', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve('Unauthorized'),
    });

    const unauthorizedSpy = vi.fn();
    window.addEventListener('auth:unauthorized', unauthorizedSpy);

    await expect(api.emails.list(0, 20)).rejects.toThrow('Unauthorized');

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('refreshToken')).toBeNull();
    expect(unauthorizedSpy).toHaveBeenCalledTimes(1);
    expect(window.location.pathname).toBe('/login');
    window.removeEventListener('auth:unauthorized', unauthorizedSpy);
  });
});
