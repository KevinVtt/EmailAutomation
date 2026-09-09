import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';
import { api } from '../lib/api';
import { useWebSocket } from '../hooks/useWebSocket';
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
      summary: vi.fn(),
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
      if (filters.destacados === 'true') return { totalElements: 12 };
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

  it('renders the Resumen de hoy card with the daily summary', async () => {
    api.emails.count.mockResolvedValue({ count: 120 });
    api.emails.filter.mockResolvedValue({ totalElements: 0 });
    api.emails.summary.mockResolvedValue({
      summary: 'Hoy recibiste 3 correos importantes.',
      emailCount: 3,
      date: '2026-09-09',
    });

    renderDashboard();

    expect(await screen.findByText('Resumen de hoy')).toBeTruthy();
    expect(await screen.findByText('Hoy recibiste 3 correos importantes.')).toBeTruthy();
    // Date is rendered via date-fns format in local timezone — match the shape, not the exact day.
    expect(await screen.findByText(/3 correos · \d{2}\/\d{2}\/\d{4}/)).toBeTruthy();
  });

  it('shows the urgent banner when an urgent_emails notification arrives', async () => {
    api.emails.count.mockResolvedValue({ count: 120 });
    api.emails.filter.mockResolvedValue({ totalElements: 0 });
    api.emails.summary.mockResolvedValue({ summary: 'Sin novedades.', emailCount: 0, date: '2026-09-09' });

    renderDashboard();

    expect(await screen.findByText('Total de emails')).toBeTruthy();

    const handlers = useWebSocket.mock.calls[0][1];
    act(() => {
      handlers.onNotifications({ type: 'notification', message: 'urgent_emails:2:Factura vencida | Urgente' });
    });

    expect(await screen.findByText(/2 correo\(s\) urgente\(s\): Factura vencida \| Urgente/)).toBeTruthy();
  });

  it('dismisses the urgent banner when the close button is clicked', async () => {
    api.emails.count.mockResolvedValue({ count: 120 });
    api.emails.filter.mockResolvedValue({ totalElements: 0 });
    api.emails.summary.mockResolvedValue({ summary: 'Sin novedades.', emailCount: 0, date: '2026-09-09' });

    renderDashboard();

    expect(await screen.findByText('Total de emails')).toBeTruthy();

    const handlers = useWebSocket.mock.calls[0][1];
    act(() => {
      handlers.onNotifications({ type: 'notification', message: 'urgent_emails:1:Factura vencida' });
    });

    expect(await screen.findByText(/1 correo\(s\) urgente\(s\): Factura vencida/)).toBeTruthy();

    fireEvent.click(screen.getByLabelText('Descartar aviso de correos urgentes'));

    await waitFor(() => {
      expect(screen.queryByText(/1 correo\(s\) urgente\(s\): Factura vencida/)).toBeNull();
    });
  });

  it('renders the daily summary as formatted markdown', async () => {
    api.emails.count.mockResolvedValue({ count: 120 });
    api.emails.filter.mockResolvedValue({ totalElements: 0 });
    api.emails.summary.mockResolvedValue({
      summary: '## Resumen general\n\n**Importante:** pago vencido hoy.\n\n- Item uno\n- Item dos',
      emailCount: 2,
      date: '2026-09-09',
    });

    renderDashboard();

    expect(await screen.findByRole('heading', { name: 'Resumen general' })).toBeTruthy();
    expect(screen.getByText('Importante:')).toBeTruthy();
    expect(screen.queryByText('**Importante:**')).toBeNull();
    expect(screen.getByText('Item uno')).toBeTruthy();
    expect(screen.getByText('Item dos')).toBeTruthy();
  });
});