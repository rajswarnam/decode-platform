# PostgreSQL Docker Setup Guide

This document explains the changes made to run PostgreSQL in Docker instead of locally.

## Changes Made

### 1. docker-compose.yaml

Added PostgreSQL service:
- **Image**: `postgres:15`
- **Database**: `decode`
- **User**: `decode_user`
- **Password**: `decode_password`
- **Port**: `5432` (exposed to host for development access)
- **Volume**: `./infra/postgres_data` (persists data)
- **Init script**: `./infra/postgres/init.sql` (runs on first start)

Updated all services to:
- Use `postgres:5432` instead of `host.docker.internal:5432`
- Add `depends_on` with `service_healthy` condition to wait for PostgreSQL
- Set `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` environment variables

### 2. application.yaml Files

Updated in:
- `context-orchestrator/src/main/resources/application.yaml`
- `ingestion-engine/src/main/resources/application.yaml`
- `code-parser/src/main/resources/application.yaml`
- `vectorizer-service/src/main/resources/application.yaml`

Changed:
- `url`: `jdbc:postgresql://postgres:5432/decode`
- `username`: `${SPRING_DATASOURCE_USERNAME:decode_user}` (uses env var with default)
- `password`: `${SPRING_DATASOURCE_PASSWORD:decode_password}` (uses env var with default)

### 3. Qdrant Host Updates

Updated Qdrant host references:
- `vectorizer-service`: Changed from `host.docker.internal` to `qdrant`
- `context-orchestrator`: Changed from `host.docker.internal` to `qdrant`

### 4. .gitignore

Added `infra/postgres_data/` to ignore PostgreSQL data directory.

## Database Credentials

**Default credentials** (can be overridden via environment variables):
- **Database**: `decode`
- **User**: `decode_user`
- **Password**: `decode_password`

## Usage

### Start PostgreSQL with all services:

```bash
docker-compose up -d
```

### Start only PostgreSQL:

```bash
docker-compose up -d postgres
```

### Access PostgreSQL from host machine:

```bash
# Using psql
psql -h localhost -p 5432 -U decode_user -d decode

# Using Docker exec
docker-compose exec postgres psql -U decode_user -d decode
```

### Check PostgreSQL logs:

```bash
docker-compose logs postgres
```

### Stop PostgreSQL:

```bash
docker-compose stop postgres
```

### Remove PostgreSQL (and data):

```bash
docker-compose down -v postgres
```

## First-Time Setup

1. Ensure `infra/postgres/init.sql` exists (it should create the schema)
2. Run `docker-compose up -d postgres`
3. Verify database is created: `docker-compose exec postgres psql -U decode_user -d decode -c "\dt"`

## Data Persistence

PostgreSQL data is stored in `./infra/postgres_data/` (ignored by git).

To backup:
```bash
docker-compose exec postgres pg_dump -U decode_user decode > backup.sql
```

To restore:
```bash
docker-compose exec -T postgres psql -U decode_user decode < backup.sql
```

## Troubleshooting

### Connection refused:
- Ensure PostgreSQL is running: `docker-compose ps postgres`
- Check logs: `docker-compose logs postgres`
- Verify health check: `docker-compose exec postgres pg_isready -U decode_user -d decode`

### Authentication failed:
- Verify credentials match in `docker-compose.yaml` and `application.yaml`
- Check environment variables: `docker-compose exec <service> env | grep SPRING_DATASOURCE`

### Port 5432 already in use:
- If you have a local PostgreSQL running on 5432, stop it or change the port in `docker-compose.yaml`:
  ```yaml
  ports:
    - "5433:5432"  # Use 5433 on host instead
  ```

## Migration Notes

If migrating from local PostgreSQL:

1. **Export data from local PostgreSQL:**
   ```bash
   pg_dump -h localhost -U kothuparotta decode > migrate_backup.sql
   ```

2. **Start Docker PostgreSQL:**
   ```bash
   docker-compose up -d postgres
   ```

3. **Import data into Docker PostgreSQL:**
   ```bash
   docker-compose exec -T postgres psql -U decode_user decode < migrate_backup.sql
   ```

4. **Verify migration:**
   ```bash
   docker-compose exec postgres psql -U decode_user decode -c "\dt"
   ```
