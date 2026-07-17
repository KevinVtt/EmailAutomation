const API_URL = import.meta.env.VITE_API_URL || '';

let refreshPromise = null;

function getToken() {
  return localStorage.getItem('accessToken');
}

async function refreshToken() {
  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    const refreshToken = localStorage.getItem('refreshToken');
    if (!refreshToken) return null;

    try {
      const res = await fetch(`${API_URL}/api/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });
      if (!res.ok) return null;
      const data = await res.json();
      localStorage.setItem('accessToken', data.accessToken);
      localStorage.setItem('refreshToken', data.refreshToken);
      return data.accessToken;
    } catch {
      return null;
    } finally {
      refreshPromise = null;
    }
  })();

  return refreshPromise;
}

async function request(endpoint, options = {}) {
  let token = getToken();
  const headers = {
    'Content-Type': 'application/json',
    ...options.headers,
  };

  if (token && endpoint !== '/api/auth/refresh') {
    headers['Authorization'] = `Bearer ${token}`;
  }

  let response = await fetch(`${API_URL}${endpoint}`, {
    ...options,
    headers,
  });

  if (response.status === 401 && endpoint !== '/api/auth/refresh') {
    const newToken = await refreshToken();
    if (newToken) {
      headers['Authorization'] = `Bearer ${newToken}`;
      response = await fetch(`${API_URL}${endpoint}`, {
        ...options,
        headers,
      });
    }
  }

  if (response.status === 401) {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    window.location.href = '/login';
    throw new Error('Unauthorized');
  }

  if (!response.ok) {
    const error = await response.text();
    throw new Error(error || `HTTP ${response.status}`);
  }

  const contentType = response.headers.get('content-type') || '';
  const text = await response.text();

  if (contentType.includes('application/json') && text) {
    return JSON.parse(text);
  }

  return text || null;
}

export const api = {
  auth: {
    getOAuthUrl: (provider) =>
      request(`/api/auth/oauth2/${provider}`),
    callback: (provider, code) =>
      request(`/api/auth/callback/${provider}`, {
        method: 'POST',
        body: JSON.stringify({ authorizationCode: code, provider }),
      }),
    refresh: async (refreshToken) => {
      const res = await fetch(`${API_URL}/api/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });
      if (!res.ok) throw new Error('Refresh failed');
      return res.json();
    },
  },
  emails: {
    list: (page = 0, size = 20) =>
      request(`/api/emails?page=${page}&size=${size}`),
    filter: (filters, page = 0, size = 20) => {
      const params = new URLSearchParams({ page, size, ...filters });
      return request(`/api/emails/filter?${params}`);
    },
    action: (provider, emailId, action) =>
      request('/api/emails/action', {
        method: 'POST',
        body: JSON.stringify({ provider, emailId, action }),
      }),
    get: (id) =>
      request(`/api/emails/${id}`),
    sync: (provider) =>
      request('/api/emails/sync', {
        method: 'POST',
        body: JSON.stringify({ provider }),
      }),
    syncStatus: () =>
      request('/api/emails/sync/status').catch(() => ({ syncing: false })),
  },
  chat: {
    send: (message, conversationId = '') =>
      request('/api/chat', {
        method: 'POST',
        body: JSON.stringify({ message, conversationId }),
      }),
  },
};
