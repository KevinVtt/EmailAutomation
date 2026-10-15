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
      stats: vi.fn(),
      list: vi.fn(),
      sync: vi.fn(),
      syncFull: vi.fn(),
      syncStatus: vi.fn(),
      summary: vi.fn(),
      prioritySummary: vi.fn(),
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

// Shape of GET /api/emails/stats (7 days, oldest first).
function statsFixture({ total = 120, unread = 30, starred = 12, spam = 8, dayCount = 0 } = {}) {
  return {
    total,
    unread,
    starred,
    spam,
    perDay: Array.from({ length: 7 }, (_, i) => ({
      date: `2026-10-0${i + 1}`,
      count: dayCount,
    })),
  };
}

describe('Dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Skip the auto-sync effect so tests don't trigger sync API calls.
    sessionStorage.setItem('autoSynced', 'true');
  });

  it('shows a loading skeleton while stats are being fetched', () => {
    api.emails.stats.mockReturnValue(new Promise(() => {}));

    renderDashboard();

    expect(screen.getByTestId('dashboard-loading')).toBeTruthy();
  });

  it('releases the sync UI when the status poll never reports completion', async () => {
    vi.useFakeTimers();
    try {
      // Deja correr el auto-sync de este test (beforeEach lo desactiva).
      sessionStorage.removeItem('autoSynced');
      api.emails.count.mockResolvedValue({ count: 0 });
      api.emails.stats.mockResolvedValue(statsFixture());
      api.emails.syncFull.mockResolvedValue({ status: 'syncing', mode: 'full' });
      api.emails.syncStatus.mockResolvedValue({ syncing: true });
      api.emails.summary.mockResolvedValue({ summary: 'Sin novedades.', emailCount: 0, date: '2026-09-09' });
      api.emails.prioritySummary.mockResolvedValue({ urgentes: 0, destacadosSinLeer: 0, pendientesRespuesta: 0 });

      renderDashboard();

      await act(async () => { await vi.advanceTimersByTimeAsync(50); });
      expect(screen.getByText('Sincronizando...')).toBeTruthy();

      // El poll nunca ve terminar la sync; el timer de seguridad (10 min) debe
      // sacar la UI de 'syncing' igual (antes quedaba pegada para siempre).
      await act(async () => { await vi.advanceTimersByTimeAsync(600001); });
      expect(screen.queryByText('Sincronizando...')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('renders the stat cards and the 7-day chart from the single stats endpoint', async () => {
    api.emails.stats.mockResolvedValue(statsFixture({ dayCount: 5 }));

    renderDashboard();

    // Stat card values
    expect(await screen.findByText('120')).toBeTruthy();
    expect(screen.getByText('30')).toBeTruthy();
    expect(screen.getByText('12')).toBeTruthy();
    expect(screen.getByText('8')).toBeTruthy();

    // Chart title + exactly 7 bars (one per day), dd/MM labels kept from the old chart
    expect(await screen.findByText('Emails por día')).toBeTruthy();
    expect(screen.getAllByTestId('chart-bar')).toHaveLength(7);
    expect(screen.getAllByTestId('chart-bar')[0].getAttribute('title')).toBe('01/10: 5 emails');

    // The four stat labels are present
    expect(screen.getByText('Total de emails')).toBeTruthy();
    expect(screen.getByText('No leídos')).toBeTruthy();
    expect(screen.getByText('Destacados')).toBeTruthy();
    expect(screen.getByText('Spam')).toBeTruthy();

    // One request instead of the old 4 + 7 filter/count calls
    expect(api.emails.stats).toHaveBeenCalledTimes(1);
    expect(api.emails.filter).not.toHaveBeenCalled();
    expect(api.emails.count).not.toHaveBeenCalled();
  });

  it('shows an error state with retry when the stats fetch fails', async () => {
    api.emails.stats.mockRejectedValue(new Error('Network error'));

    renderDashboard();

    expect(await screen.findByText('Error al cargar estadísticas')).toBeTruthy();

    // Retry re-fetches the stats
    const retry = screen.getByText('Reintentar');
    api.emails.stats.mockResolvedValue(statsFixture({ total: 3 }));
    fireEvent.click(retry);

    await waitFor(() => {
      expect(screen.getByText('3')).toBeTruthy();
    });
  });

  it('renders the Resumen de hoy card with the daily summary', async () => {
    api.emails.stats.mockResolvedValue(statsFixture());
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
    api.emails.stats.mockResolvedValue(statsFixture());
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
    api.emails.stats.mockResolvedValue(statsFixture());
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
    api.emails.stats.mockResolvedValue(statsFixture());
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

  it('renders the Resumen priorizado card with the three priority numbers', async () => {
    // starred=7 so the "12" assertion below is unambiguous against the stat cards
    api.emails.stats.mockResolvedValue(statsFixture({ starred: 7 }));
    api.emails.summary.mockResolvedValue({ summary: 'Sin novedades.', emailCount: 0, date: '2026-09-09' });
    api.emails.prioritySummary.mockResolvedValue({
      urgentes: 3,
      destacadosSinLeer: 12,
      pendientesRespuesta: 5,
    });

    renderDashboard();

    expect(await screen.findByText('Resumen priorizado')).toBeTruthy();
    expect(await screen.findByText('3')).toBeTruthy();
    expect(screen.getByText('12')).toBeTruthy();
    expect(screen.getByText('5')).toBeTruthy();
    expect(screen.getByText('urgentes')).toBeTruthy();
    expect(screen.getByText('destacados sin leer')).toBeTruthy();
    expect(screen.getByText('esperando respuesta')).toBeTruthy();
  });

  it('shows an error with retry when the priority summary fetch fails', async () => {
    api.emails.stats.mockResolvedValue(statsFixture());
    api.emails.summary.mockResolvedValue({ summary: 'Sin novedades.', emailCount: 0, date: '2026-09-09' });
    api.emails.prioritySummary.mockRejectedValue(new Error('Network error'));

    renderDashboard();

    expect(await screen.findByText('Network error')).toBeTruthy();

    api.emails.prioritySummary.mockResolvedValue({ urgentes: 1, destacadosSinLeer: 2, pendientesRespuesta: 3 });
    fireEvent.click(screen.getByText('Reintentar'));

    expect(await screen.findByText('1')).toBeTruthy();
    expect(screen.getByText('2')).toBeTruthy();
    expect(screen.getByText('3')).toBeTruthy();
  });
});