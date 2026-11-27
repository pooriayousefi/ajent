# Changelog

## Unreleased

- Include nine optional C++ MCP tool servers under tools/ with a build target and example configuration.
- Use the published mcp.clj 1.0.3 dependency for MCP client support.
- Remove the obsolete REST tool servers, their configuration stub, and generated editor files.
- Ship an empty MCP server list and keep provider overrides optional and local.
- Validate model endpoint URLs and omit API key values from startup output.
- Close opened MCP connections when registry discovery fails.
