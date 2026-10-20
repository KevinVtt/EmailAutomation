import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { AuthContext } from '../context/AuthContext';
import { api } from '../lib/api';
import { useWebSocket } from '../hooks/useWebSocket';
import EmailListView from '../components/EmailListView';

vi.mock('../lib/api', () => ({
  api: {
    emails: {
      list: vi.fn(),
      filter: vi.fn(),
      get: vi.fn(),
      action: vi.fn(),
    },
  },
}));

vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: vi.fn() }));

const mockAuth = {
  user: { id: 'user-1', email: 'a@b.com', name: 'Ana', provider: 'google' },
  loading: false,
  login: vi.fn(),
  logout: vi.fn(),
};

const mockEmail = {
  id: 'e1',
  subject: 'Email destacado',
  fromAddress: 'sender@example.com',
  fromName: 'Sender',
  bodyPreview: 'Preview...',
  isStarred: true,
  labels: 'STARRED',
  receivedAt: '2024-06-15T10:00:00Z',
  provider: 'google',
};

function renderList(props = {}) {
  return render(
    <AuthContext.Provider value={mockAuth}>
      <EmailListView title="Destacados" filters={{ isStarred: true }} {...props} />
    </AuthContext.Provider>
  );
}

describe('EmailListView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('fetches emails with the given filters', async () => {
    api.emails.filter.mockResolvedValue({ content: [mockEmail], totalPages: 1 });
    renderList();

    await waitFor(() => {
      expect(api.emails.filter).toHaveBeenCalledWith({ isStarred: true }, 0);
    });
    expect(await screen.findByText('Email destacado')).toBeTruthy();
  });

  it('sends dateFrom at local midnight, not UTC midnight', async () => {
    api.emails.filter.mockResolvedValue({ content: [], totalPages: 0 });
    const { container } = renderList();
    await waitFor(() => expect(api.emails.filter).toHaveBeenCalled());

    const dateInput = container.querySelector('input[type="date"]');
    fireEvent.change(dateInput, { target: { value: '2026-10-20' } });

    await waitFor(() => {
      const lastCall = api.emails.filter.mock.calls.at(-1);
      expect(lastCall[0].dateFrom).toBe(new Date('2026-10-20T00:00:00').toISOString());
    });
  });

  it('renders the section title', async () => {
    api.emails.filter.mockResolvedValue({ content: [], totalPages: 0 });
    renderList();
    expect(await screen.findByText('Destacados')).toBeTruthy();
  });

  it('shows the empty message when there are no emails', async () => {
    api.emails.filter.mockResolvedValue({ content: [], totalPages: 0 });
    renderList({ emptyMessage: 'No tienes emails destacados' });
    expect(await screen.findByText('No tienes emails destacados')).toBeTruthy();
  });

  it('shows an error state when the fetch fails', async () => {
    api.emails.filter.mockRejectedValue(new Error('Network error'));
    renderList();
    expect(await screen.findByText('Error al cargar emails')).toBeTruthy();
  });

  it('removes the email from the list when deleted from the detail', async () => {
    const email = { ...mockEmail, providerEmailId: 'p1' };
    api.emails.filter.mockResolvedValue({ content: [email], totalPages: 1 });
    api.emails.get.mockResolvedValue({ ...email, visto: true, bodyHtml: '<p>Hola</p>' });
    api.emails.action.mockResolvedValue({});
    renderList();

    fireEvent.click(await screen.findByText('Email destacado'));
    fireEvent.click(await screen.findByTitle('Eliminar'));

    await waitFor(() => expect(api.emails.action).toHaveBeenCalledWith('google', 'p1', 'trash'));
    await waitFor(() => expect(screen.queryByText('Email destacado')).toBeNull());
  });

  it('refreshes the list when a background sync completes', async () => {
    api.emails.filter.mockResolvedValue({ content: [mockEmail], totalPages: 1 });
    renderList();
    await screen.findByText('Email destacado');
    expect(api.emails.filter).toHaveBeenCalledTimes(1);

    const { onNotifications } = useWebSocket.mock.calls[0][1];
    await act(async () => {
      onNotifications({ type: 'notification', message: 'sync_complete' });
    });

    await waitFor(() => expect(api.emails.filter).toHaveBeenCalledTimes(2));
  });
});