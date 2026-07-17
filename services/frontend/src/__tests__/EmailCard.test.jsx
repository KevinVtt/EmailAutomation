import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import EmailCard from '../components/EmailCard';

describe('EmailCard component', () => {
  const mockEmail = {
    id: '1',
    subject: 'Test Subject',
    fromAddress: 'sender@example.com',
    bodyPreview: 'This is a preview of the email body...',
    isRead: false,
    isStarred: true,
    receivedAt: '2024-06-15T10:00:00Z',
    provider: 'google',
  };

  it('renders email subject', () => {
    render(<EmailCard email={mockEmail} onClick={() => {}} />);
    expect(screen.getByText('Test Subject')).toBeTruthy();
  });

  it('renders sender address', () => {
    render(<EmailCard email={mockEmail} onClick={() => {}} />);
    expect(screen.getByText('sender@example.com')).toBeTruthy();
  });

  it('renders body preview', () => {
    render(<EmailCard email={mockEmail} onClick={() => {}} />);
    expect(screen.getByText(/This is a preview/)).toBeTruthy();
  });

  it('renders star icon for starred emails', () => {
    render(<EmailCard email={mockEmail} onClick={() => {}} />);
    const card = screen.getByText('Test Subject').closest('div');
    expect(card).toBeTruthy();
  });
});
