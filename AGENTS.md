# Repository Guidelines

## Project Structure & Module Organization

LANDrop is a Java 21 Maven project with two modules:

- `backend/src/main/java/com/landrop/`: discovery, TCP transfers, chat, authentication, WebSocket services, and database access.
- `frontend/src/main/java/com/landrop/ui/`: JavaFX controllers, models, API clients, and state services; depends on `backend`.
- Each module keeps tests in `src/test/java/`, mirroring production packages.
- `frontend/src/main/resources/com/landrop/ui/` contains FXML views and `styles.css`. Backend resources contain the admin page and SQL migrations in `db/migration/`.
- `docs/` holds architecture, protocol, API contracts, and test plans; `prisma/schema.prisma` describes the database schema.

## Build, Test, and Development Commands

Use JDK 21 and Maven 3.9+; run these commands from the repository root:

- `mvn clean install`: build, test, and install both modules locally, including the backend dependency needed by frontend launches.
- `mvn test`: run both modules' default test suites.
- `mvn -pl backend test`: run backend tests only.
- `mvn -pl frontend -am test`: test the frontend and required backend module.
- `mvn -pl frontend javafx:run`: launch the desktop application after installation.

For local discovery demos, run `./start-discovery.sh`, `./start-peerA.sh`, and `./start-peerB.sh` in separate terminals. Windows equivalents are `.\start-discovery.bat`, `.\start-peerA.bat`, and `.\start-peerB.bat`. Discovery uses port 8888; peers use distinct ports 5001 and 5002.

## Coding Style & Naming Conventions

Follow surrounding Java code: four-space indentation, same-line opening braces, `PascalCase` classes, `camelCase` methods and fields, and `UPPER_SNAKE_CASE` constants. Keep packages under `com.landrop`; use descriptive suffixes such as `Controller`, `Service`, and `Repository`. Name FXML views like `settings-view.fxml`. No formatter or linter is configured in the Maven POMs.

## Testing Guidelines

Tests use JUnit 5 through Maven Surefire. Name classes `*Test` or `*IntegrationTest`; cover changed behavior and relevant failure cases. No numeric coverage threshold is configured. Consult `docs/TEST_PLAN.md` for scenarios. Database tests may require `DATABASE_URL`; opt-in PostgreSQL suites additionally require `"-Dlandrop.test.postgres=true"`. Use a dedicated test database and report skipped tests.

## Commit & Pull Request Guidelines

Recent commits use prefixes such as `feat:` and `refactor:` followed by an imperative summary. Follow that pattern and keep changes focused. PRs should describe behavior changes, link relevant issues, report test commands/results, and include screenshots for UI changes. Update affected protocol or API documentation.

## Configuration & Secrets

Use `.env.example` as the configuration reference. Keep credentials in local configuration; never commit `.env` or session files. Add database migrations using the existing `V<number>__description.sql` naming pattern.
