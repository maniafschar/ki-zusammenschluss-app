# Requirements MCP Server

The server provides a six-step AI-readiness questionnaire over MCP Streamable HTTP. It exposes each question and its answer options, records the model's sufficiency decision, and enforces the multiple-selection limits for steps 3 and 4.

Run the MCP server in one terminal:

```sh
cd requirements-mcp-server
mvn spring-boot:run
```

Then start the website application from the repository root with the MCP workflow enabled:

```sh
APP_REQUIREMENTS_MCP_ENABLED=true mvn spring-boot:run
```

The website connects to `http://127.0.0.1:8091/mcp` by default. Override the address with `APP_REQUIREMENTS_MCP_URL`. The server binds to loopback by default; keep it private or add authentication and network controls before exposing it beyond the local machine.

Questionnaire progress is currently held in memory by the MCP server and expires after 24 hours of inactivity. It is lost if the server restarts.