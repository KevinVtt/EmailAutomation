import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { LoadingState, ErrorState, EmptyState } from '../components/states';

describe('Shared state components', () => {
  describe('LoadingState', () => {
    it('renders a spinner with a label by default', () => {
      render(<LoadingState label="Cargando emails..." />);
      expect(screen.getByText('Cargando emails...')).toBeTruthy();
    });

    it('renders skeleton rows in skeleton variant', () => {
      const { container } = render(<LoadingState variant="skeleton" rows={3} />);
      expect(container.querySelectorAll('.animate-pulse')).toHaveLength(3);
    });

    it('renders custom skeleton children when provided', () => {
      render(
        <LoadingState variant="skeleton" testId="custom-skeleton">
          <div>Custom skeleton</div>
        </LoadingState>
      );
      expect(screen.getByTestId('custom-skeleton')).toBeTruthy();
      expect(screen.getByText('Custom skeleton')).toBeTruthy();
    });
  });

  describe('ErrorState', () => {
    it('renders message, detail and retry button that triggers onRetry', () => {
      const onRetry = vi.fn();
      render(
        <ErrorState message="Error al cargar" detail="Network error" onRetry={onRetry} />
      );
      expect(screen.getByText('Error al cargar')).toBeTruthy();
      expect(screen.getByText('Network error')).toBeTruthy();
      fireEvent.click(screen.getByText('Reintentar'));
      expect(onRetry).toHaveBeenCalledTimes(1);
    });

    it('does not render a retry button when onRetry is not provided', () => {
      render(<ErrorState message="Error" />);
      expect(screen.queryByText('Reintentar')).toBeNull();
    });
  });

  describe('EmptyState', () => {
    it('renders message and hint', () => {
      render(
        <EmptyState message="No hay emails" hint="Sincroniza para ver tus emails" />
      );
      expect(screen.getByText('No hay emails')).toBeTruthy();
      expect(screen.getByText('Sincroniza para ver tus emails')).toBeTruthy();
    });
  });
});