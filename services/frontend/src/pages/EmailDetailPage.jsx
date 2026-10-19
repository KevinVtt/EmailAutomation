import { useCallback, useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { api } from '../lib/api';
import EmailDetail from '../components/EmailDetail';
import { LoadingState, ErrorState } from '../components/states';

/**
 * Vista completa de un email abierto desde el asistente (o por URL directa).
 * Ruta propia para que el detalle use toda la pantalla y "atrás" vuelva a la
 * lista donde estabas; si se entró por URL directa, vuelve al inbox.
 */
export default function EmailDetailPage() {
  const { id } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  // Desde el asistente ya viene el DTO completo; por URL directa se busca.
  const [email, setEmail] = useState(
    location.state?.email?.id === id ? location.state.email : null
  );
  const [error, setError] = useState(null);

  const back = useCallback(() => {
    if (location.key === 'default') navigate('/inbox');
    else navigate(-1);
  }, [location.key, navigate]);

  useEffect(() => {
    if (email) return;
    api.emails.get(id)
      .then(setEmail)
      .catch(() => setError('No se pudo abrir el email.'));
  }, [email, id]);

  return (
    <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-6 h-[calc(100vh-7rem)]">
      {error ? (
        <ErrorState message={error} onRetry={back} retryLabel="Volver" />
      ) : email ? (
        <EmailDetail email={email} onClose={back} />
      ) : (
        <LoadingState label="Abriendo email..." />
      )}
    </div>
  );
}
