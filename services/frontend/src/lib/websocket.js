import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const WS_URL = import.meta.env.VITE_WS_URL || '';

let client = null;
const subscriptions = new Map();
const pendingSubscriptions = [];

function applyPendingSubscriptions() {
  for (const { topic, callback } of pendingSubscriptions) {
    const sub = client.subscribe(topic, (message) => {
      try {
        const data = JSON.parse(message.body);
        callback(data);
      } catch (e) {
        callback(message.body);
      }
    });
    subscriptions.set(topic, sub);
  }
  pendingSubscriptions.length = 0;
}

export function connectWebSocket(token, handlers = {}) {
  if (client?.active) {
    return client;
  }

  client = new Client({
    webSocketFactory: () => new SockJS(`${WS_URL}/ws`),
    connectHeaders: {
      Authorization: `Bearer ${token}`,
    },
    debug: () => {},
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    onConnect: () => {
      applyPendingSubscriptions();
      if (handlers.onConnect) handlers.onConnect();
    },
    onStompError: (frame) => {
      console.error('STOMP error', frame);
      if (handlers.onError) handlers.onError(frame);
    },
  });

  client.activate();
  return client;
}

export function subscribeToTopic(topic, callback) {
  if (!client?.active) {
    pendingSubscriptions.push({ topic, callback });
    return { unsubscribe: () => {} };
  }

  const subscription = client.subscribe(topic, (message) => {
    try {
      const data = JSON.parse(message.body);
      callback(data);
    } catch (e) {
      callback(message.body);
    }
  });

  subscriptions.set(topic, subscription);
  return subscription;
}

export function subscribeToUser(userId, queue, callback) {
  return subscribeToTopic(`/user/${userId}/${queue}`, callback);
}

export function unsubscribe(topic) {
  const sub = subscriptions.get(topic);
  if (sub) {
    sub.unsubscribe();
    subscriptions.delete(topic);
  }
  const idx = pendingSubscriptions.findIndex((s) => s.topic === topic);
  if (idx !== -1) pendingSubscriptions.splice(idx, 1);
}

export function sendMessage(destination, body = {}) {
  if (!client?.active) {
    console.warn('WebSocket not connected');
    return;
  }
  client.publish({
    destination,
    body: JSON.stringify(body),
  });
}

export function disconnectWebSocket() {
  subscriptions.clear();
  pendingSubscriptions.length = 0;
  if (client) {
    client.deactivate();
    client = null;
  }
}
