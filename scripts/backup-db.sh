#!/usr/bin/env bash
# Backup diario de Postgres. Cron sugerido: 0 3 * * * /opt/emailfilter/scripts/backup-db.sh
# shortcut: backup en el mismo disco de la VM, copiar fuera de la VM (rclone/scp) cuando haya tiempo
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
mkdir -p backups
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" | gzip > "backups/emailfilter-$(date +%F).sql.gz"
find backups -name 'emailfilter-*.sql.gz' -mtime +14 -delete
