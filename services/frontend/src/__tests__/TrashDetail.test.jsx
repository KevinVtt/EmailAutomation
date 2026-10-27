import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import EmailDetail from '../components/EmailDetail';

vi.mock('../lib/api', () => ({
  api: {
    emails: {
      get: vi.fn(),
      action: vi.fn(),
    },
  },
}));

import { api } from '../lib/api';

const base = { id: 'e1', provider: 'google', providerEmailId: 'g1', subject: 'Hola', fromAddress: 'a@x.com', visto: true };

describe('EmailDetail in the trash', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.emails.get.mockResolvedValue({ ...base, labels: 'TRASH', isStarred: false });
    api.emails.action.mockResolvedValue({});
  });

  it('offers restore and permanent delete instead of trash', async () => {
    render(<EmailDetail email={{ ...base, labels: 'TRASH' }} onClose={() => {}} onAction={() => {}} />);

    expect(await screen.findByTitle('Restaurar')).toBeTruthy();
    expect(screen.getByTitle('Eliminar definitivamente')).toBeTruthy();
    expect(screen.queryByTitle('Eliminar')).toBeNull();
  });

  it('asks before deleting forever and does nothing when cancelled', async () => {
    vi.spyOn(window, 'confirm').mockReturnValueOnce(false);
    render(<EmailDetail email={{ ...base, labels: 'TRASH' }} onClose={() => {}} onAction={() => {}} />);

    fireEvent.click(await screen.findByTitle('Eliminar definitivamente'));

    expect(window.confirm).toHaveBeenCalled();
    expect(api.emails.action).not.toHaveBeenCalledWith('google', 'g1', 'delete');
  });

  it('restores through the action endpoint and notifies the list', async () => {
    const onAction = vi.fn();
    render(<EmailDetail email={{ ...base, labels: 'TRASH' }} onClose={() => {}} onAction={onAction} />);

    fireEvent.click(await screen.findByTitle('Restaurar'));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith('restore'));
    expect(api.emails.action).toHaveBeenCalledWith('google', 'g1', 'restore');
  });
});

describe('EmailDetail in spam', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.emails.get.mockResolvedValue({ ...base, labels: 'SPAM', isStarred: false });
    api.emails.action.mockResolvedValue({});
  });

  it('offers to remove it from spam and to delete it forever', async () => {
    const onAction = vi.fn();
    render(<EmailDetail email={{ ...base, labels: 'SPAM' }} onClose={() => {}} onAction={onAction} />);

    expect(await screen.findByTitle('Quitar de spam')).toBeTruthy();
    expect(screen.getByTitle('Eliminar definitivamente')).toBeTruthy();
    expect(screen.queryByTitle('Eliminar')).toBeNull();

    fireEvent.click(screen.getByTitle('Quitar de spam'));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith('unspam'));
    expect(api.emails.action).toHaveBeenCalledWith('google', 'g1', 'unspam');
  });

  it('lists the mail immediately and refetches when the provider fails', async () => {
    vi.spyOn(window, 'confirm').mockReturnValueOnce(true);
    api.emails.action.mockRejectedValueOnce(new Error('403'));
    const onAction = vi.fn();
    render(<EmailDetail email={{ ...base, labels: 'SPAM' }} onClose={() => {}} onAction={onAction} />);

    fireEvent.click(await screen.findByTitle('Eliminar definitivamente'));

    expect(onAction).toHaveBeenCalledWith('delete');
    await waitFor(() => expect(onAction).toHaveBeenCalledWith('delete:failed'));
  });
});
