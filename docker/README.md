# Deploy com Docker

## Pré-requisitos

- Docker 20+
- Docker Compose v2+

## Configuração

Copiar o arquivo de exemplo e preencher com as credenciais:

```bash
cd docker/
cp .env.example .env
# Editar .env com as credenciais reais
```

## Subir o sistema

```bash
cd docker/
docker compose up -d
```

Isso cria 3 containers:

| Container | Serviço | Porta |
|---|---|---|
| nusp-oracle | Oracle 21c XE | 1521 (interna) |
| nusp-backend | Spring Boot (Java 17) | 8003 (interna) |
| nusp-frontend | Angular + Nginx | 80 |

## Primeiro uso — criar schema

Após o Oracle estar pronto (~1-2 minutos na primeira vez):

```bash
# Criar usuário NUSP (substituir as senhas pelas do .env)
docker exec -i nusp-oracle sqlplus -s "sys/<ORACLE_PASSWORD>@XEPDB1 as sysdba" <<EOF
CREATE USER NUSP IDENTIFIED BY <ORACLE_APP_PASSWORD>
  DEFAULT TABLESPACE USERS TEMPORARY TABLESPACE TEMP
  QUOTA UNLIMITED ON USERS;
GRANT CONNECT, RESOURCE, CREATE SESSION, CREATE TABLE,
      CREATE SEQUENCE, CREATE VIEW, CREATE PROCEDURE TO NUSP;
EOF

# Importar estrutura do banco
docker cp ../nusp-schema.sql nusp-oracle:/tmp/
docker exec -i nusp-oracle sqlplus "NUSP/<ORACLE_APP_PASSWORD>@XEPDB1" @/tmp/nusp-schema.sql
```

## Acessar

- **Sistema:** http://localhost
- **Health check:** http://localhost:8003/api/health
- **Oracle:** `docker exec -it nusp-oracle sqlplus NUSP/<senha>@XEPDB1`

## Atualizar após mudanças no código

```bash
cd docker/
docker compose build
docker compose up -d
```

## Parar

```bash
docker compose down           # para os containers (mantém dados)
docker compose down -v        # para e remove volumes (apaga banco)
```
