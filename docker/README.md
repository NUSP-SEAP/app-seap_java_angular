# Deploy com Docker

## Pré-requisitos

- Docker 20+
- Docker Compose v2+

## Subir o sistema

```bash
cd docker/
docker-compose up -d
```

Isso cria 3 containers:

| Container | Serviço | Porta |
|---|---|---|
| nusp-oracle | Oracle 21c XE | 1521 |
| nusp-backend | Spring Boot (Java 17) | 8003 |
| nusp-frontend | Angular + Nginx | 80 |

## Primeiro uso — criar schema e importar dados

Após o Oracle estar pronto (~1-2 minutos na primeira vez):

```bash
# Criar usuário NUSP
docker exec -i nusp-oracle sqlplus -s sys/SenNusp2026@XEPDB1 as sysdba <<EOF
CREATE USER NUSP IDENTIFIED BY NuspApp2026
  DEFAULT TABLESPACE USERS TEMPORARY TABLESPACE TEMP
  QUOTA UNLIMITED ON USERS;
GRANT CONNECT, RESOURCE, CREATE SESSION, CREATE TABLE,
      CREATE SEQUENCE, CREATE VIEW, CREATE PROCEDURE TO NUSP;
EOF

# Importar schema + dados
docker cp ../nusp-export-full.sql nusp-oracle:/tmp/
docker exec -i nusp-oracle sqlplus NUSP/NuspApp2026@XEPDB1 @/tmp/nusp-export-full.sql
```

## Acessar

- **Sistema:** http://localhost
- **Health check:** http://localhost:8003/api/health
- **Oracle:** `docker exec -it nusp-oracle sqlplus NUSP/NuspApp2026@XEPDB1`

## Atualizar após mudanças no código

```bash
cd docker/
docker-compose build
docker-compose up -d
```

## Parar

```bash
docker-compose down           # para os containers (mantém dados)
docker-compose down -v        # para e remove volumes (apaga banco)
```
