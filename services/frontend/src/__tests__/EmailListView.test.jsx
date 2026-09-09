import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { AuthContext } from '../context/AuthContext';
import { api } from '../lib/api';
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
});