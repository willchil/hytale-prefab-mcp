# PrefabMcp

Hosts an [MCP](https://modelcontextprotocol.io) server inside a Hytale server so an agentic harness
can author prefabs against that server's own live block palette.

Four tools: search the palette, look at a block, run a build script, render the result.

```
search_blocks   ->  real names from the running server, mods included
get_block_texture -> face texture, or the inventory icon for model-drawn blocks
build_prefab    ->  runs your JavaScript, writes prefabs/<name>.prefab.json
render_prefab   ->  PNG from any camera angle, so the agent can see what it built
```

## Build and install

Needs Java 25 and Maven.

```
mvn package
cp target/PrefabMcp-1.0.0.jar <server>/mods/
```

Start the server and look for:

```
[PrefabMcp|P] Palette snapshot: 2981 blocks and fluids
[PrefabMcp|P] MCP server listening on http://127.0.0.1:8765/mcp
```

Run `/prefab-mcp` in game or on the server console and it prints the configuration to paste,
with the address the listener actually took:

```
MCP server listening on port 8765
Add this to your MCP client configuration, replacing <server-host> with this server's
address (127.0.0.1 if it is the same machine as the agent):
{
  "mcpServers": {
    "hytale-prefab": {
      "type": "http",
      "url": "http://<server-host>:8765/mcp"
    }
  }
}
```

The port is known; the host is not. Where the agent runs relative to the server, and whether a
reverse proxy or DNS name sits in front, is not something the server can see, so it asks rather than
guesses. Note that the listener is bound to loopback, so today the answer is `127.0.0.1` and a remote
agent needs a tunnel: `ssh -L 8765:127.0.0.1:8765 user@server`.

Or point an agent at it directly:

```
claude mcp add --transport http hytale-prefab http://127.0.0.1:8765/mcp
```

The listener binds to loopback only, and refuses requests carrying a cross-origin `Origin` header, so
it is reachable by an agent on the same machine and by nothing else.

### Permission

`/prefab-mcp` requires `games.crescentnetwork.prefabmcp.command`, derived from the manifest's `Group`
and `Name` so it follows them rather than drifting. The node is logged at startup:

```
[PrefabMcp|P] Registered /prefab-mcp (permission: games.crescentnetwork.prefabmcp.command)
```

It is not attached to any built-in permission group, so it has to be granted deliberately. The
`hytale:Admin` group carries a `*` wildcard and therefore already has it, which is why a singleplayer
owner can run the command without configuring anything.

### Configuration

On first load the plugin writes `mods/games.crescentnetwork_PrefabMcp/mcp.json`:

```json
{
  "port": 7520
}
```

The port it starts with is the game's own bind port plus 2000, so several servers on one machine do
not collide. After that the file is the source of truth and is never rewritten, so an edit survives a
restart and keys this version does not recognise are left alone.

That matters most for a **singleplayer world**, which the client launches on an ephemeral port that
changes every time. The first load picks whatever the offset gives that session and writes it down;
from then on the address is fixed. If you want to choose it yourself, edit `port` and restart — and if
you are setting up an agent against it, do that first so the address never moves.

`-Dhytale.mcp.port=<n>` overrides the file for one run without writing back to it.

If the chosen port is already taken the plugin logs a warning and stays off rather than quietly
binding somewhere else, because a server on an address nobody is pointed at is worse than no server.

## The palette is live

`search_blocks` reads `BlockType.getAssetMap()` and `Fluid.getAssetMap()` from the running server, not
a checked-in list, so a block added by another mod shows up automatically and is attributed to its
pack. That matters because Hytale resolves an unrecognised block name to `BlockType.UNKNOWN` without
raising, so a typo is otherwise invisible until you look at the build in game. Here an unknown name
fails the build and comes back with suggestions.

## Fluids go through `block()`

There is no separate `fluid()` call. Pass a fluid name to any placement function and it lands in the
prefab's fluid layer:

```js
box(-12, 0, -12, 12, 0, 12, 'Water_Source');   // fills a lake
block(0, 0, 0, 'Plant_Seaweed_Dead_Stack');
block(0, 0, 0, 'Water');                        // same cell, both layers
```

Three reasons this is merged rather than split:

- The 14 fluid ids collide with none of the 2,952 block ids, even case-insensitively, so a name is
  never ambiguous.
- Every geometry helper works on fluids for free. A separate API would have needed its own
  `fluidBox`, `fluidSphere`, `fluidLine`.
- Blocks and fluids are separate layers, so one cell can hold both. That is not a corner case: the
  shipped prefab corpus has 15,659 such cells, nearly all underwater plants and coral.

Fluid level is inferred from the name and can be overridden with `{ level: n }`. Every one of the
154,143 `*_Source` cells in the base prefabs is level 1, with no exceptions, while flowing names peak
at their maximum level, so a `*_Source` name means a full source cell and anything else means full
flow.

## Script API

Plain JavaScript. No TypeScript, no modules, no I/O.

```js
block(x, y, z, name [, opts])
box(x1, y1, z1, x2, y2, z2, name [, opts])          opts {hollow: true} for a shell
line(x1, y1, z1, x2, y2, z2, name [, opts])
sphere(cx, cy, cz, r, name [, opts])
ellipsoid(cx, cy, cz, rx, ry, rz, name [, opts])
cylinder(cx, cy, cz, r, h, name [, axis] [, opts])  axis "x" | "y" (default) | "z"

blockAt(x, y, z) / fluidAt(x, y, z)                 name placed there, or null
clear(x, y, z)                                      remove from both layers
mirrorX(p) / mirrorY(p) / mirrorZ(p)                duplicate everything across a plane
findBlocks({query, group, drawType, kind, limit})   array of names
nearestBlock('#8a8a8a')                             closest block by average colour
setAnchor(x, y, z)                                  prefab anchor, default 0,0,0
rng()                                               seeded, reproducible for a given seed
log(message)                                        returned with the result
```

`opts` carries `{rotation: n}` for blocks, `{level: n}` for fluids, `{hollow: true}` for the volume
primitives. Y must be between -512 and 511, because prefabs pack Y into nine signed bits. Later
writes to a coordinate replace earlier ones.

Output is sparse: only cells the script placed are written, so pasting overlays terrain rather than
clearing a box around the build. Hytale's paste UI has an air-override toggle for the other case.

## Rendering

Hytale renders blocks on the client, so there is no server-side renderer to borrow. `render_prefab` is
a plain raycaster: one ray per pixel, marched through the voxel grid, shaded from the face normal it
entered through. Cubic blocks sample their real face texture. The roughly 1,784 model-drawn blocks
have no face texture and render as solid cubes in their average colour, so thin geometry like ropes
and torches looks chunkier than in game. It is a massing and proportion check, not a screenshot.

Water gets its colour from the most common `WaterTint` across the loaded environments, multiplied
into its texture average, because water ships a near-white greyscale texture and is tinted per-biome
at render time.

A 33x32x33 build renders at 640x480 in under 60ms.

## Sandbox

Host access off, no host class lookup, no IO, no threads, no native access, no environment access. The
API reaches the script as polyglot proxies rather than as Java objects, so there is nothing to walk
back into the JVM through. A runaway script is stopped by cancelling its context from a watchdog,
which is the only thing that interrupts a spinning loop.

Nothing here touches the world, so no world-thread marshalling is needed. Palette snapshots are taken
under `AssetRegistry.ASSET_LOCK`.

## Tests

```
mvn test
```

40 tests, no server required: the script engine and geometry run against a stand-in palette. Set
`MCP_RENDER_DUMP=<dir>` to also write sample renders for eyeballing.
