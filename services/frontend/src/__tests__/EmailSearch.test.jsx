import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import EmailListView from '../components/EmailListView';

vi.mock('../lib/api', () => ({
  api: {
    emails: {
      list: vi.fn(),
      filter: vi.fn(),
      search: vi.fn(),
      get: vi.fn(),
    },
  },
}));
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ user: { provider: 'google' } }) }));
vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: vi.fn() }));

import { api } from '../lib/api';

describe('EmailListView search', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.emails.filter.mockResolvedValue({ content: [], totalPages: 0 });
    api.emails.search.mockResolvedValue({ content: [], totalPages: 0 });
  });

  it('searches the active folder with the typed text after the debounce', async () => {
    render(<EmailListView title="Spam" filters={{ label: 'SPAM' }} folder="spam" />);
    await waitFor(() => expect(api.emails.filter).toHaveBeenCalled());

    fireEvent.change(screen.getByLabelText('Buscar en Spam'), { target: { value: '  factura ' } });

    await waitFor(() => expect(api.emails.search).toHaveBeenCalledWith('factura', 'spam', 0));
  });
});
