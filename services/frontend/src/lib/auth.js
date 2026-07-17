export function isTokenExpired(token) {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.exp * 1000 < Date.now();
  } catch {
    return true;
  }
}

export function getStoredAuth() {
  try {
    const accessToken = localStorage.getItem('accessToken');
    const refreshToken = localStorage.getItem('refreshToken');
    const user = localStorage.getItem('user');

    if (accessToken && user) {
      if (isTokenExpired(accessToken)) {
        clearAuth();
        return null;
      }
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

export function storeAuth(authResponse) {
  localStorage.setItem('accessToken', authResponse.accessToken);
  localStorage.setItem('refreshToken', authResponse.refreshToken);
  localStorage.setItem('user', JSON.stringify({
    id: authResponse.userId,
    email: authResponse.email,
    name: authResponse.name,
    avatarUrl: authResponse.avatarUrl,
  }));
}

export function clearAuth() {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('user');
}
