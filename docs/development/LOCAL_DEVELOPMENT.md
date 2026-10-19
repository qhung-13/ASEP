# ASEP — Local Development Guide

**Project:** Agentic Software Engineering Platform (ASEP)
**Scope:** M01-02 — Application Bootstrap
**Environment:** Ubuntu, localhost-first

## 1. Overview

ASEP currently contains two application foundations:

| Application | Technology                            | Local URL             |
| ----------- | ------------------------------------- | --------------------- |
| Backend     | Java 21, Spring Boot, Spring Modulith | http://127.0.0.1:8080 |
| Frontend    | Next.js, TypeScript, pnpm             | http://127.0.0.1:3000 |

The applications can start independently.

This bootstrap does not yet implement GitLab integrations, authentication, AI inference, background workflows, or publication.

## 2. Prerequisites

The following versions were verified during M01-02:

| Tool            | Verified version  |
| --------------- | ----------------- |
| Java JDK        | OpenJDK 21.0.12.1 |
| Node.js         | 24.21.0           |
| Corepack        | 0.36.0            |
| pnpm            | 12.11.2           |
| Spring Boot     | 4.1.1             |
| Spring Modulith | 2.1.1             |
| Next.js         | 16.4.2            |

Maven is managed through Maven Wrapper (`./mvnw`).
A global Maven installation is not required.

### Java configuration

The development machine also has other JDK versions installed.

To select Java 21 for the current terminal session:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

java -version
javac -version
```

Both commands should report Java 21.

These exports apply only to the current terminal session. Adjust `JAVA_HOME` if Java 21 is installed at a different path.

## 3. Backend Development

Open a terminal at the repository root:

```bash
cd backend
```

Ensure Java 21 is selected.

### Run tests

```bash
./mvnw test
```

The current foundation includes:

- `AsepApplicationTests`: Spring application context test.
- `AsepArchitectureTests`: Spring Modulith architecture verification.

The Modulith verification mechanism has been tested, but actual business module boundaries do not exist yet.

### Build executable JAR

```bash
./mvnw verify
```

Expected artifact:

```text
target/asep-backend-0.0.1-SNAPSHOT.jar
```

### Start backend

```bash
./mvnw spring-boot:run
```

The default configuration binds the HTTP server to:

```text
127.0.0.1:8080
```

Keep the terminal running.

### Verify backend health

From a separate terminal:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

Expected health status:

```json
{"status":"UP"}
```

The actual response may include additional health groups.

## 4. Frontend Development

Open another terminal at the repository root:

```bash
cd frontend
```

### Install dependencies

```bash
corepack pnpm install
```

pnpm is pinned through the `packageManager` field in `package.json`.

The repository uses `pnpm-lock.yaml`. Do not generate or commit an npm `package-lock.json` for this application.

The pnpm build-script policy is defined in `pnpm-workspace.yaml`.

### Run development server

```bash
corepack pnpm dev --hostname 127.0.0.1 --port 3000
```

Open:

```text
http://127.0.0.1:3000
```

Keep the frontend terminal running.

### Run ESLint

```bash
corepack pnpm lint
```

### Generate Next.js types and check TypeScript

```bash
corepack pnpm exec next typegen
corepack pnpm exec tsc --noEmit
```

`next typegen` generates framework-provided helpers such as `LayoutProps`.

### Build frontend

```bash
corepack pnpm build
```

## 5. Local Network and Security

- Do not expose development services publicly by default.
- Do not commit `.env` files, credentials, access tokens, or private keys.
- Do not introduce external AI inference services.
- PostgreSQL, Keycloak, and local inference runtimes are not required for M01-02.
- Backend and frontend integration/authentication are future implementation tasks.
- The backend binds to `127.0.0.1` by default through `application.properties`.
- The frontend binds to `127.0.0.1` when started with the documented `--hostname` option. Do not assume a bare `pnpm dev` command is localhost-onl

## 6. Verification Status

The following were observed during M01-02:

- Backend Maven tests: 2 passed, 0 failures.
- Backend `./mvnw verify`: BUILD SUCCESS.
- Backend health: HTTP 200, status UP.
- Backend localhost binding: verified.
- Frontend ESLint: passed.
- Frontend TypeScript: passed.
- Frontend production build: passed.
- Frontend HTTP response: HTTP 200.
- Landing page: manually confirmed.

These are bootstrap verification results, not evidence that M01 business workflows are implemented.

Fresh-clone reproducibility and subsequent infrastructure integrations still require separate verification.
