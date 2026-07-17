import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ChatPanel from '../components/ChatPanel';

describe('ChatPanel component', () => {
  beforeEach(() => {
    localStorage.setItem('accessToken', 'test-token');
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({
        response: 'Showing important emails.',
        criteria: { important: 'true' },
        conversationId: 'conv-123',
      })),
    });
  });

  it('renders welcome message', () => {
    render(<ChatPanel onFiltersApplied={() => {}} />);
    expect(screen.getByText(/Puedes pedirme que filtre tus emails/i)).toBeTruthy();
  });

  it('renders input field and send button', () => {
    render(<ChatPanel onFiltersApplied={() => {}} />);
    expect(screen.getByPlaceholderText(/Escribe un filtro/i)).toBeTruthy();
    expect(screen.getByRole('button')).toBeTruthy();
  });

  it('sends message and displays response', async () => {
    const onFiltersApplied = vi.fn();
    render(<ChatPanel onFiltersApplied={onFiltersApplied} />);

    const input = screen.getByPlaceholderText(/Escribe un filtro/i);
    fireEvent.change(input, { target: { value: 'Show important emails' } });
    fireEvent.click(screen.getByRole('button'));

    await waitFor(() => {
      expect(screen.getByText('Showing important emails.')).toBeTruthy();
    });
  });
});
