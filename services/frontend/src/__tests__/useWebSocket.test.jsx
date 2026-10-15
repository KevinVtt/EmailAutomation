import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useWebSocket } from '../hooks/useWebSocket';

const { connectWebSocket, disconnectWebSocket, subscribeToTopic, unsubscribe } = vi.hoisted(() => ({
  connectWebSocket: vi.fn(),
  disconnectWebSocket: vi.fn(),
  subscribeToTopic: vi.fn(),
  unsubscribe: vi.fn(),
}));

vi.mock('../lib/websocket', () => ({
  connectWebSocket,
  disconnectWebSocket,
  subscribeToTopic,
  unsubscribe,
}));

const makeToken = (sub) => `header.${btoa(JSON.stringify({ sub }))}.sig`;

describe('useWebSocket', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('connects with the access token and subscribes to the user queues on connect', () => {
    const handlers = { onEmails: vi.fn(), onChat: vi.fn(), onNotifications: vi.fn() };
    const { unmount } = renderHook(() => useWebSocket(makeToken('user-123'), handlers));

    expect(connectWebSocket).toHaveBeenCalledWith(makeToken('user-123'), expect.any(Object));

    const { onConnect } = connectWebSocket.mock.calls[0][1];
    act(() => onConnect());

    // Destinations must not carry a userId — the server resolves it from the session.
    expect(subscribeToTopic).toHaveBeenCalledWith('/user/queue/emails', expect.any(Function));
    expect(subscribeToTopic).toHaveBeenCalledWith('/user/queue/chat', expect.any(Function));
    expect(subscribeToTopic).toHaveBeenCalledWith('/user/queue/notifications', expect.any(Function));
    for (const [topic] of subscribeToTopic.mock.calls) {
      expect(topic).not.toContain('user-123');
    }

    unmount();
    expect(unsubscribe).toHaveBeenCalledWith('/user/queue/emails');
    expect(unsubscribe).toHaveBeenCalledWith('/user/queue/chat');
    expect(unsubscribe).toHaveBeenCalledWith('/user/queue/notifications');
    expect(disconnectWebSocket).toHaveBeenCalled();
  });

  it('connects even when the JWT payload has no sub claim (no userId extraction)', () => {
    const token = `header.${btoa(JSON.stringify({ role: 'user' }))}.sig`;
    renderHook(() => useWebSocket(token, {}));

    expect(connectWebSocket).toHaveBeenCalledWith(token, expect.any(Object));
  });

  it('reconnects with the new token when the token changes', () => {
    const { rerender } = renderHook(({ token }) => useWebSocket(token, {}), {
      initialProps: { token: makeToken('user-1') },
    });

    expect(connectWebSocket).toHaveBeenCalledWith(makeToken('user-1'), expect.any(Object));

    rerender({ token: makeToken('user-2') });

    expect(disconnectWebSocket).toHaveBeenCalled();
    expect(connectWebSocket).toHaveBeenCalledWith(makeToken('user-2'), expect.any(Object));
  });

  it('does not connect when there is no token', () => {
    renderHook(() => useWebSocket(null, {}));

    expect(connectWebSocket).not.toHaveBeenCalled();
  });
});
