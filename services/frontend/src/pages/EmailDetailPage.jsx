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
  // Se deriva de la ruta: al abrir otro email cambia el id, no se reusa el anterior.
  const stateEmail = location.state?.email?.id === id ? location.state.email : null;
  const [fetched, setFetched] = useState(null);
  const [failure, setFailure] = useState(null);
  const email = stateEmail || (fetched?.id === id ? fetched.email : null);
  // El error también es de un id: al abrir otro email no debe mostrarse el anterior.
  // Con un email disponible (DTO del asistente o copia ya bajada) el error viejo no aplica.
  const error = !email && failure?.id === id ? failure.message : null;

  const back = useCallback(() => {
    if (location.key === 'default') navigate('/inbox');
    else navigate(-1);
  }, [location.key, navigate]);

  useEffect(() => {
    if (stateEmail) return;
    let alive = true;
    api.emails.get(id)
      .then((data) => {
        if (!alive) return;
        setFetched({ id, email: data });
        setFailure(null);
      })
      .catch(() => {
        if (alive) setFailure({ id, message: 'No se pudo abrir el email.' });
      });
    return () => {
      alive = false;
    };
  }, [id, stateEmail]);

  return (
    <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-6 h-[calc(100vh-7rem)]">
      {error ? (
        <ErrorState message={error} onRetry={back} retryLabel="Volver" />
      ) : email ? (
        <EmailDetail key={email.id} email={email} onClose={back} />
      ) : (
        <LoadingState label="Abriendo email..." />
      )}
    </div>
  );
}
