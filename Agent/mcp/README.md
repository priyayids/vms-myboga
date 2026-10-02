# MCP (Model Context Protocol) Tools & Integrations

This directory maintains MCP server configurations and guides for AI agent integration with local development resources.

## Available / Recommended MCP Configurations

### 1. PostgreSQL MCP Server
Allows direct database querying and inspection of tables, migrations, and visitor records.
Config schema for `mcp_config.json`:
```json
{
  "mcpServers": {
    "postgres": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-postgres",
        "postgresql://postgres:postgres@localhost:5432/visitor_bridge"
      ]
    }
  }
}
```

### 2. Nuveq API OpenAPI MCP Server
Enables real-time dynamic querying and schema introspection of Nuveq Partner API endpoints:
```json
{
  "mcpServers": {
    "nuveq-api": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-openapi",
        "Agent/notes/nuveq-openapi.json"
      ]
    }
  }
}
```
