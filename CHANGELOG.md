# Changelog

## 2.3.0

- Adopt the SAURON configuration layout, nested MCP transports, and startup flags.
- Include nine optional C++ MCP tool servers under tools/ with a build target and example configuration.
- Use the published mcp.clj 1.0.3 dependency for MCP client support.
- Remove the obsolete REST tool servers, their configuration stub, and generated editor files.
- Enable the nine bundled servers in the default configuration.
- Show progress while waiting for the model and while executing approved tools.
- Validate model endpoint URLs and omit API key values from startup output.
- Close opened MCP connections when registry discovery fails.
