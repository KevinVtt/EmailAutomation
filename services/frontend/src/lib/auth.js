export function getStoredAuth() {
  try {
    const accessToken = localStorage.getItem('accessToken');
    const refreshToken = localStorage.getItem('refreshToken');
    const user = localStorage.getItem('user');

    if (accessToken && user) {
      return {
        accessToken,
        refreshToken,
        user: JSON.parse(user),
      };
    }
  } catch (e) {
    console.error('Failed to parse stored auth data:', e);
    clearAuth();
  }
  return null;
}

export function storeAuth(authResponse, provider) {
  localStorage.setItem('accessToken', authResponse.accessToken);
  localStorage.setItem('refreshToken', authResponse.refreshToken);
  localStorage.setItem('user', JSON.stringify({
    id: authResponse.userId,
    email: authResponse.email,
    name: authResponse.name,
    avatarUrl: authResponse.avatarUrl,
    provider,
  }));
}

export function clearAuth() {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('user');
}
