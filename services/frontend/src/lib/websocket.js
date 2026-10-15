import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const WS_URL = import.meta.env.VITE_WS_URL || '';

let client = null;

// La conexión es única para toda la app: cada componente registra acá sus
// suscripciones y se las vuelve a enganchar solo tras una reconexión.
const subscriptions = new Set();
const connectListeners = new Set();
const errorListeners = new Set();

function attach(handle) {
  handle.stomp = client.subscribe(handle.topic, (message) => {
    try {
      handle.callback(JSON.parse(message.body));
    } catch (e) {
      handle.callback(message.body);
    }
  });
}

// La baja puede caer sobre una conexión ya cerrada (reconexión, logout): no debe
// cortar el resto del reenganche.
function detach(handle) {
  try {
    handle.stomp?.unsubscribe();
  } catch (e) {
    console.warn('WebSocket unsubscribe ignored', e);
  }
  handle.stomp = null;
}

export function connectWebSocket(token) {
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
    // El access token vence solo: en cada intento hay que mandar el vigente o la
    // reconexión queda pegada con uno vencido y el canal muere.
    beforeConnect: () => {
      const fresh = localStorage.getItem('accessToken');
      if (fresh) {
        client.connectHeaders = { Authorization: `Bearer ${fresh}` };
      }
    },
    onConnect: () => {
      for (const handle of subscriptions) {
        detach(handle);
        attach(handle);
      }
      connectListeners.forEach((listener) => listener());
    },
    onStompError: (frame) => {
      console.error('STOMP error', frame);
      errorListeners.forEach((listener) => listener(frame));
    },
  });

  client.activate();
  return client;
}

/**
 * Suscribe a un topic y devuelve la función para darla de baja. Se puede
 * llamar antes de conectar: la suscripción queda encolada.
 */
export function subscribeToTopic(topic, callback) {
  const handle = { topic, callback, stomp: null };
  subscriptions.add(handle);
  if (client?.connected) {
    attach(handle);
  }
  return () => {
    subscriptions.delete(handle);
    detach(handle);
  };
}

/** Avisa cuando la conexión (re)conecta. Si ya está conectada, dispara de una. */
export function onWebSocketConnect(listener) {
  connectListeners.add(listener);
  if (client?.connected) {
    listener();
  }
  return () => connectListeners.delete(listener);
}

export function onWebSocketError(listener) {
  errorListeners.add(listener);
  return () => errorListeners.delete(listener);
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
  connectListeners.clear();
  errorListeners.clear();
  if (client) {
    client.deactivate();
    client = null;
  }
}
