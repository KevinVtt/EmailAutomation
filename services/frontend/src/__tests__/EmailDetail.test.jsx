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

  it('does not mark as read the email from a stale response after another one opened', async () => {
    let resolveFirst;
    api.emails.get
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({ id: 'e2', provider: 'google', providerEmailId: 'm2', visto: true, bodyHtml: '<p>B</p>' });
    api.emails.action.mockResolvedValue({ status: 'ok' });
    const onRead = vi.fn();

    const { rerender } = render(<EmailDetail email={mockEmail} onClose={() => {}} onRead={onRead} />);
    rerender(<EmailDetail email={{ id: 'e2', provider: 'google', providerEmailId: 'm2' }} onClose={() => {}} onRead={onRead} />);
    await waitFor(() => expect(api.emails.get).toHaveBeenCalledTimes(2));

    resolveFirst({ id: 'e1', provider: 'google', providerEmailId: 'm1', visto: false, bodyHtml: '<p>A</p>' });
    await waitFor(() => expect(api.emails.action).toHaveBeenCalledWith('google', 'm1', 'read'));
    expect(onRead).not.toHaveBeenCalled();
  });
});
