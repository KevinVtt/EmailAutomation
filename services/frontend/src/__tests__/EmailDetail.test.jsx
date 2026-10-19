import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { api } from '../lib/api';
import EmailDetail from '../components/EmailDetail';

vi.mock('../lib/api', () => ({
  api: {
    emails: {
      get: vi.fn(),
      action: vi.fn(),
    },
  },
}));

const mockEmail = { id: 'e1', provider: 'google', providerEmailId: 'm1' };

describe('EmailDetail star action', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('refetches the email after starring so the UI shows what the server saved', async () => {
    api.emails.get.mockResolvedValue({
      id: 'e1',
      provider: 'google',
      providerEmailId: 'm1',
      isStarred: false,
      visto: true,
      bodyHtml: '<p>Hola</p>',
    });
    api.emails.action.mockResolvedValue({ status: 'ok' });

    render(<EmailDetail email={mockEmail} onClose={() => {}} />);

    fireEvent.click(await screen.findByTitle('Destacar'));

    await waitFor(() => expect(api.emails.get).toHaveBeenCalledTimes(2));
  });
});
