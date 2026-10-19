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
    const { container } = render(<EmailCard email={mockEmail} onClick={() => {}} />);
    expect(container.querySelector('.fill-yellow-400')).toBeTruthy();
  });

  it('renders no star icon when the server says the email is not starred', () => {
    const { container } = render(
      <EmailCard email={{ ...mockEmail, isStarred: false }} onClick={() => {}} />
    );
    expect(container.querySelector('.fill-yellow-400')).toBeNull();
  });
});
