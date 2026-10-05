# Repository Guidelines

## Project Structure & Module Organization

LANDrop has a Java 21 Maven backend and a separate Next.js frontend:

- `backend/src/main/java/com/landrop/`: discovery, TCP transfers, chat, authentication, WebSocket services, and database access.
- `frontend/app/`, `frontend/components/`, and `frontend/lib/`: Next.js routes, React components, types, state, realtime, and WebRTC services. Frontend currently includes mock data and simulated flows.
- Backend tests are in `backend/src/test/java/`, mirroring production packages. Launcher tests are in `scripts/*.test.mjs`.
- Backend resources contain the admin HTML page and SQL migrations in `db/migration/`.
- `docs/api/`, `docs/architecture/`, and `docs/guides/` hold technical documentation; `docs/README.md` indexes it. `prisma/schema.prisma` describes the database schema.

## Build, Test, and Development Commands

Use JDK 21 and Maven 3.9+; run these commands from the repository root:

- `mvn clean install`: build, test, and install the backend module locally.
- `mvn test`: run the backend's default test suites.
- `mvn -pl backend test`: run backend tests only.
- `npm --prefix frontend ci`: install locked frontend dependencies with Node.js 20+.
- `npm run dev` / `npm run build`: develop or build the Next.js frontend.
- `npm test`: run server launcher tests.
- `npm run server:backend`: launch the integrated Java backend.

For local discovery demos, use `scripts/demo/start-discovery.*` and the peer CLI commands in `docs/guides/CLI_DEMO.md`. Discovery uses port 8888; example peers use ports 5001 and 5002. Frontend and backend both default to HTTP 3000; use different ports when running both locally.

## Coding Style & Naming Conventions

Follow surrounding Java code: four-space indentation, same-line opening braces, `PascalCase` classes, `camelCase` methods and fields, and `UPPER_SNAKE_CASE` constants. Keep packages under `com.landrop`; use descriptive suffixes such as `Controller`, `Service`, and `Repository`. Follow surrounding TypeScript/React conventions in frontend. No formatter or linter is configured in the Maven POMs.

## Testing Guidelines

Tests use JUnit 5 through Maven Surefire. Name classes `*Test` or `*IntegrationTest`; cover changed behavior and relevant failure cases. No numeric coverage threshold is configured. Consult `docs/TEST_PLAN.md` for scenarios. Database tests may require `DATABASE_URL`; opt-in PostgreSQL suites additionally require `"-Dlandrop.test.postgres=true"`. Use a dedicated test database and report skipped tests.

## Commit & Pull Request Guidelines

Recent commits use prefixes such as `feat:` and `refactor:` followed by an imperative summary. Follow that pattern and keep changes focused. PRs should describe behavior changes, link relevant issues, report test commands/results, and include screenshots for UI changes. Update affected protocol or API documentation.

## Configuration & Secrets

Use `.env.example` as the configuration reference. Keep credentials in local configuration; never commit `.env` or session files. Add database migrations using the existing `V<number>__description.sql` naming pattern.
