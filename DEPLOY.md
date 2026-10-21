# Deploy gratuito (Oracle Cloud + DuckDNS + Caddy)

Resultado: la app en `https://TUAPP.duckdns.org`, con HTTPS automático, Gmail con consentimiento directo
(sin agregar usuarios a mano). Límite: menos de 100 usuarios, y Google muestra la advertencia
"app no verificada" (cada usuario pulsa *Avanzado → Ir a la app*).

Todo es gratis, pero Oracle pide tarjeta para validar la cuenta. No se cobra si usas solo recursos *Always Free*.

---

## 1. Dominio gratis (DuckDNS)

1. Entra a https://www.duckdns.org y accede con GitHub, Google o similar.
2. Crea un subdominio, por ejemplo `miempresa-mail`. Queda `miempresa-mail.duckdns.org`.
3. Déjalo pendiente: la IP se pone cuando tengas la VM (paso 2).

## 2. VM gratis en Oracle Cloud

1. Crea cuenta en https://www.oracle.com/cloud/free/ (tarjeta para validar).
2. Elige una región cercana a tus usuarios.
3. *Compute → Instances → Create instance*:
   - **Image:** Canonical Ubuntu 22.04 o 24.04.
   - **Shape:** *Ampere* `VM.Standard.A1.Flex`, **4 OCPU y 24 GB** (todo gratis dentro del límite Always Free). Usa 4 GB como mínimo.
   - **Networking:** asigna IP pública.
   - **SSH keys:** genera un par y **descarga la clave privada**. Sin ella no entras.
4. Si Oracle responde "Out of capacity" para A1: prueba otra hora o otra región. Es muy común.
5. Anota la **IP pública** de la instancia.
6. Apunta DuckDNS a esa IP (en el panel de DuckDNS, campo *current ip*).

### Abrir puertos 80 y 443 (dos lugares, ambos obligatorios)

a) **Firewall de Oracle:** *Networking → Virtual Cloud Networks → tu VCN → Security Lists →* regla de entrada (ingress):
   - Source `0.0.0.0/0`, TCP, destino `80` y `443`.

b) **Firewall dentro de la VM** (la imagen de Ubuntu de Oracle trae reglas que bloquean todo menos SSH). Conéctate por SSH y ejecuta:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

Si `netfilter-persistent` no existe: `sudo apt install -y iptables-persistent` y repite el `save`.

## 3. Conectarse y preparar Docker

Desde tu PC (PowerShell o terminal), con la clave privada descargada:

```bash
ssh -i ruta/a/tu-clave.key ubuntu@IP_PUBLICA
```

En la VM:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
exit   # vuelve a entrar por SSH para que aplique el grupo
```

Swap de 4 GB (margen para los límites de memoria):

```bash
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 4. Google Cloud (OAuth para Gmail)

Si el proyecto ya existe, reutilízalo. Si no, crea uno en https://console.cloud.google.com.

1. *APIs & Services → Library* → busca **Gmail API** → *Enable*.
2. *Google Auth Platform / OAuth consent screen*:
   - User type: **External**. Nombre de la app, correo de soporte, dominio autorizado `duckdns.org`.
   - Página de inicio y política de privacidad: URL pública (la app puede mostrar una página simple; Google la pide).
   - Scopes: `gmail.readonly` y `gmail.modify` (los que usa la app).
   - **Publishing status → PUBLISH APP → "In production".** Sin esto solo entran test users.
3. *Credentials → Create credentials → OAuth client ID*:
   - Application type: **Web application**.
   - **Authorized redirect URI:** `https://TUAPP.duckdns.org/auth/callback` (con tu subdominio real).
   - Copia **Client ID** y **Client secret** para el `.env`.

Google mostrará la advertencia de app no verificada. Es esperado (ver arriba).

## 5. Clave de Groq (IA del chat)

Cuenta gratis en https://console.groq.com → *API Keys* → crear. Esa es `AI_API_KEY`.

## 6. Secretos: qué generas tú

Tú decides estos valores; no vienen de ningún servicio. Genéralos en la VM (no los pegues en chats ni en el repo):

| Variable | Qué es | Cómo |
|---|---|---|
| `POSTGRES_PASSWORD` | contraseña de la base | `openssl rand -hex 24` |
| `JWT_SECRET` | firma los tokens de sesión | `openssl rand -hex 32` |
| `TOKEN_ENCRYPTION_KEY` | cifra los tokens OAuth de Gmail en la base | `openssl rand -hex 32` |

**Importante:**
- **`TOKEN_ENCRYPTION_KEY` no se puede cambiar después** sin perder el acceso a Gmail de todos los usuarios (los tokens ya guardados no se descifrarían). Guárdala en un gestor de contraseñas.
- `JWT_SECRET` cambiarlo solo cierra sesiones.
- Los valores de Google y Groq (paso 4 y 5) son "secretos" de terceros; igual, no los compartas.

## 7. Clonar y configurar

```bash
sudo apt install -y git
git clone -b deploy/prod-free https://github.com/TU_USUARIO/TU_REPO.git /opt/emailfilter
cd /opt/emailfilter
cp .env.example .env
nano .env
```

Rellena en `.env` (solo estas; el resto puede quedar por defecto):

```
DOMAIN=TUAPP.duckdns.org
POSTGRES_PASSWORD=...           # del paso 6
JWT_SECRET=...                  # del paso 6
TOKEN_ENCRYPTION_KEY=...        # del paso 6
GOOGLE_CLIENT_ID=...            # paso 4
GOOGLE_CLIENT_SECRET=...        # paso 4
AI_API_KEY=...                  # paso 5
```

Ojo: `GOOGLE_REDIRECT_URI` y `FRONTEND_URL` **no** se editan: `docker-compose.prod.yml` los arma con `DOMAIN`.

## 8. Arrancar

```bash
cd /opt/emailfilter
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml logs -f caddy
```

- Primera vez: el build de backend tarda varios minutos.
- Caddy pide el certificado a Let's Encrypt. Si el DNS y los puertos están bien, aparece "certificate obtained". Abre `https://TUAPP.duckdns.org`.
- Si falla el certificado: revisa que DuckDNS apunte a la IP y que los puertos 80/443 estén abiertos (paso 2).

La base se crea sola la primera vez (scripts de `infrastructure/postgres/init`). Los cambios de esquema posteriores son manuales: la app usa `ddl-auto: validate`.

## 9. Backups diarios

```bash
chmod +x scripts/backup-db.sh
crontab -e
```

Añade:

```
0 3 * * * /opt/emailfilter/scripts/backup-db.sh
```

Los dumps quedan en `/opt/emailfilter/backups/` (14 días). Están en el mismo disco de la VM: copia alguno fuera de Oracle de vez en cuando.

## 10. Actualizar la app

```bash
cd /opt/emailfilter
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

## Comprobaciones rápidas

- `docker compose -f docker-compose.prod.yml ps`: todos `Up`, postgres `healthy`.
- Desde fuera de la VM, solo deben responder 80 y 443. El puerto 5432, 8080 y 8000 **no** deben abrir.
- Login con Google desde un correo que no sea de prueba: debe mostrar la advertencia y luego entrar.
