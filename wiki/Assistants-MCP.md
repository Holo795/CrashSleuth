# Using it from an assistant

`crashsleuth mcp` turns the tool into a Model Context Protocol server, so an assistant can read your server
**instead of guessing at it**. It speaks over standard input and output; there is no port and no daemon.

## Why bother

Ask an assistant why your server will not start and it will usually invent a plausible-sounding answer from
the log you pasted. With this it can instead:

- analyse the actual folder, jars included;
- list what is really installed, with versions and declared dependencies;
- compare a player's mods with the server's;
- read a configuration file — with secrets replaced before it ever sees them;
- ask **which settings exist and what values they accept**, rather than inventing a key that does not.

That last one matters: most bad advice about Minecraft servers is a setting that does not exist.

## Setting it up

### Claude Desktop

`claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "crashsleuth": {
      "command": "/path/to/crashsleuth/bin/crashsleuth",
      "args": ["mcp"]
    }
  }
}
```

### Claude Code

```bash
claude mcp add crashsleuth -- /path/to/crashsleuth/bin/crashsleuth mcp
```

### Anything else that speaks MCP

Run `crashsleuth mcp` as the command, with no arguments, and let it talk over stdio.

## What it offers

| Tool | What it answers |
| --- | --- |
| `analyze` | what is wrong with this folder, log or crash report |
| `inventory` | what is installed, with metadata |
| `compare` | what a player has that a server does not, and the other way round |
| `readable` | this trace, with the game's names translated |
| `config_read` | this configuration file, secrets hidden |
| `known_pairs` | mods known not to work together, with the source |
| `known_settings` | the settings that exist, their accepted values and what they do |

## What it will not do

It reads. It does not edit your files, restart your server, or install anything.

Secrets — a forwarding secret, an RCON password, a database password — are replaced before the file is
handed over, so an assistant cannot repeat them back into a chat log.
