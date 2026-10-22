import { describe, it, expect, vi, beforeEach } from 'vitest';

const { mockClientInstance } = vi.hoisted(() => {
  const mockClientInstance = {
    active: false,
    activate: vi.fn(),
    deactivate: vi.fn(),
    subscribe: vi.fn(),
    publish: vi.fn(),
  };
  return { mockClientInstance };
});

vi.mock('@stomp/stompjs', () => ({
  Client: vi.fn(() => mockClientInstance),
}));

vi.mock('sockjs-client', () => ({
  default: vi.fn(),
}));

import { Client } from '@stomp/stompjs';
import * as wsModule from '../lib/websocket';
import { connectWebSocket, disconnectWebSocket, subscribeToTopic, onWebSocketConnect } from '../lib/websocket';

describe('websocket.js', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockClientInstance.active = false;
    mockClientInstance.connected = false;
    mockClientInstance.subscribe.mockImplementation(() => ({ unsubscribe: vi.fn() }));
    disconnectWebSocket();
  });

  it('sends the access token in STOMP connect headers on every handshake', () => {
    connectWebSocket('test-access-token');

    expect(Client).toHaveBeenCalledTimes(1);
    const config = Client.mock.calls[0][0];
    expect(config.connectHeaders.Authorization).toBe('Bearer test-access-token');
    expect(mockClientInstance.activate).toHaveBeenCalledTimes(1);
  });

  it('keeps reconnect delay at 5s and heartbeat at 4s', () => {
    connectWebSocket('test-access-token');

    const config = Client.mock.calls[0][0];
    expect(config.reconnectDelay).toBe(5000);
    expect(config.heartbeatIncoming).toBe(4000);
    expect(config.heartbeatOutgoing).toBe(4000);
  });

  it('disconnect clears the client so a new connection uses fresh headers', () => {
    connectWebSocket('token-a');
    disconnectWebSocket();
    connectWebSocket('token-b');

    expect(Client).toHaveBeenCalledTimes(2);
    expect(Client.mock.calls[1][0].connectHeaders.Authorization).toBe('Bearer token-b');
  });

  it('no longer exposes subscribeToUser (user destinations carry no userId)', () => {
    expect(wsModule.subscribeToUser).toBeUndefined();
  });

  it('queues subscriptions made before connect and applies them on connect', () => {
    const off = subscribeToTopic('/user/queue/chat', vi.fn());
    subscribeToTopic('/user/queue/emails', vi.fn());
    off();
    expect(mockClientInstance.subscribe).not.toHaveBeenCalled();

    connectWebSocket('test-access-token');
    Client.mock.calls[0][0].onConnect();

    expect(mockClientInstance.subscribe).toHaveBeenCalledTimes(1);
    expect(mockClientInstance.subscribe).toHaveBeenCalledWith('/user/queue/emails', expect.any(Function));
  });

  it('keeps a subscription per component on the same topic (shared connection)', () => {
    connectWebSocket('test-access-token');
    mockClientInstance.connected = true;

    const offFirst = subscribeToTopic('/user/queue/notifications', vi.fn());
    subscribeToTopic('/user/queue/notifications', vi.fn());

    expect(mockClientInstance.subscribe).toHaveBeenCalledTimes(2);

    const [firstSub, secondSub] = mockClientInstance.subscribe.mock.results.map((r) => r.value);
    offFirst();
    expect(firstSub.unsubscribe).toHaveBeenCalled();
    expect(secondSub.unsubscribe).not.toHaveBeenCalled();
  });

  it('re-attaches the live subscriptions after a reconnect', () => {
    connectWebSocket('test-access-token');
    mockClientInstance.connected = true;
    subscribeToTopic('/user/queue/emails', vi.fn());

    const config = Client.mock.calls[0][0];
    const staleSub = mockClientInstance.subscribe.mock.results[0].value;
    config.onConnect();

    expect(staleSub.unsubscribe).toHaveBeenCalled();
    expect(mockClientInstance.subscribe).toHaveBeenCalledTimes(2);
  });

  it('notifies connect listeners on connect, and immediately when already connected', () => {
    const listener = vi.fn();
    onWebSocketConnect(listener);
    expect(listener).not.toHaveBeenCalled();

    connectWebSocket('test-access-token');
    Client.mock.calls[0][0].onConnect();
    expect(listener).toHaveBeenCalledTimes(1);

    mockClientInstance.connected = true;
    onWebSocketConnect(listener);
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('calls the handler once with the parsed body even when it throws', () => {
    connectWebSocket('test-access-token');
    mockClientInstance.connected = true;
    const callback = vi.fn(() => {
      throw new Error('boom');
    });
    subscribeToTopic('/user/queue/chat', callback);

    const onMessage = mockClientInstance.subscribe.mock.calls[0][1];
    expect(() => onMessage({ body: '{"response":"hola"}' })).toThrow('boom');
    expect(callback).toHaveBeenCalledTimes(1);
    expect(callback).toHaveBeenCalledWith({ response: 'hola' });
  });
});