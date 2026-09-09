import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';
import { api } from '../lib/api';
import Dashboard from '../pages/Dashboard';

vi.mock('../lib/api', () => ({
  api: {
    emails: {
      count: vi.fn(),
      filter: vi.fn(),
      list: vi.fn(),
      sync: vi.fn(),
      syncFull: vi.fn(),
      syncStatus: vi.fn(),
    },
  },
}));

// WebSocket would try to connect in jsdom — no-op it.
vi.mock('../hooks/useWebSocket', () => ({
  useWebSocket: vi.fn(),
}));

// ChatPanel makes its own API calls — keep the Dashboard test focused on stats.
vi.mock('../components/ChatPanel', () => ({
  default: () => <div>Asistente IA</div>,
}));

const mockAuth = {
  user: { id: 'user-1', email: 'a@b.com', name: 'Ana', provider: 'google' },
  loading: false,
  login: vi.fn(),
  logout: vi.fn(),
};

function renderDashboard() {
  return render(
    <AuthContext.Provider value={mockAuth}>
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>
    </AuthContext.Provider>
  );
}

describe('Dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Skip the auto-sync effect so tests don't trigger sync API calls.
    sessionStorage.setItem('autoSynced', 'true');
  });

  it('shows a loading skeleton while stats are being fetched', () => {
    api.emails.count.mockReturnValue(new Promise(() => {}));
    api.emails.filter.mockReturnValue(new Promise(() => {}));

    renderDashboard();

    expect(screen.getByTestId('dashboard-loading')).toBeTruthy();
  });

  it('renders the stat cards and the 7-day chart from existing endpoints', async () => {
    api.emails.count.mockResolvedValue({ count: 120 });
    api.emails.filter.mockImplementation(async (filters) => {
      if (filters.isRead === 'false') return { totalElements: 30 };
      if (filters.isStarred === 'true') return { totalElements: 12 };
      if (filters.label === 'SPAM') return { totalElements: 8 };
      // Per-day chart queries
      return { totalElements: 5 };
    });

    renderDashboard();

    // Stat card values
    expect(await screen.findByText('120')).toBeTruthy();
    expect(await screen.findByText('30')).toBeTruthy();
    expect(await screen.findByText('12')).toBeTruthy();
    expect(await screen.findByText('8')).toBeTruthy();

    // Chart title + exactly 7 bars (one per day)
    expect(await screen.findByText('Emails por día')).toBeTruthy();
    expect(screen.getAllByTestId('chart-bar')).toHaveLength(7);

    // The four stat labels are present
    expect(screen.getByText('Total de emails')).toBeTruthy();
    expect(screen.getByText('No leídos')).toBeTruthy();
    expect(screen.getByText('Destacados')).toBeTruthy();
    expect(screen.getByText('Spam')).toBeTruthy();
  });

  it('shows an error state with retry when the stats fetch fails', async () => {
    api.emails.count.mockRejectedValue(new Error('Network error'));

    renderDashboard();

    expect(await screen.findByText('Error al cargar estadísticas')).toBeTruthy();

    // Retry re-fetches the stats
    const retry = screen.getByText('Reintentar');
    api.emails.count.mockResolvedValue({ count: 3 });
    api.emails.filter.mockResolvedValue({ totalElements: 0 });
    fireEvent.click(retry);

    await waitFor(() => {
      expect(screen.getByText('3')).toBeTruthy();
    });
  });
});