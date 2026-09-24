# PrefabMcp

Hosts an [MCP](https://modelcontextprotocol.io) server inside a Hytale server so an agentic harness
can author prefabs against that server's own live block palette.

Four tools: search the palette, look at a block, run a build script, render the result.

```
search_blocks   ->  real names from the running server, mods included, with each block's size in cells
get_block_texture -> face texture, or the inventory icon for model-drawn blocks, plus footprint and rotations
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

Run `/prefab-mcp` in game and it opens a page with the configuration, and your personal token when
one is required, in fields you can select and copy. Chat cannot be selected and the protocol has no
clipboard packet, so a page is the only way to hand over a long token without retyping it. Nothing is
read back from those fields: editing one locally does nothing, and running the command again restores
it.

On the server console, where there is no client to show a page to, it prints the same configuration
instead. On a server that is [local only](#local-only), which is the default, that looks like:

```
MCP server listening on port 8765
Add this to your MCP client configuration on this machine. This server is local only, so it
accepts connections from this machine alone; to use it from other devices, set "localOnly": false
in mcp.json and restart.
{
  "mcpServers": {
    "hytale-prefab": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

Or point an agent on the same machine at it directly:

```
claude mcp add --transport http hytale-prefab http://127.0.0.1:8765/mcp
```

### Local only

By default the listener binds to the loopback address (`127.0.0.1`), the address a machine uses to
talk to itself. Traffic to it never leaves the machine, so only an agent running on the same machine
as the Hytale server can connect, and `/prefab-mcp` always gives `127.0.0.1` as the host.

To use it from other devices, set `localOnly` to `false` in `mcp.json` and restart. The listener then
binds to `0.0.0.0`, every network address the machine has, and accepts connections from any device
that can reach the port. `/prefab-mcp` then prints a `<server-host>` placeholder instead, because
where the agent runs relative to the server, and whether a reverse proxy or DNS name sits in front, is
not something the server can see, so it asks rather than guesses:

```
Add this to your MCP client configuration. Replace <server-host> with this server's address, or
127.0.0.1 if the agent runs on this machine.
{
  "mcpServers": {
    "hytale-prefab": {
      "type": "http",
      "url": "http://<server-host>:8765/mcp",
      "headers": {
        "Authorization": "Bearer 657827e9-8e16-4614-b533-4cd444373a46.HvjnLeoLyTwzJ-8kMPhP69Vze-7MXHZz"
      }
    }
  }
}
```

Before turning it off:

- **Turn on personal tokens.** With `localOnly` false and `requirePersonalToken` false, anyone who
  can reach the port may use every tool, and the plugin says so at boot:

  ```
  [PrefabMcp|P] "localOnly" is false and personal tokens are not required: the plugin's MCP endpoint
                can be used by anyone on the internet who can reach this port. Set
                "requirePersonalToken": true in mcp.json to require one.
  ```

- **The endpoint is plain HTTP.** Tokens travel unencrypted, so anyone on the network path can read
  one. On anything but a trusted network, put a TLS reverse proxy in front instead, or leave
  `localOnly` on and reach it through an SSH tunnel:
  `ssh -L 8765:127.0.0.1:8765 user@server`, then use `http://127.0.0.1:8765/mcp` on the agent's
  machine.
- **The port must be reachable**: open it in the host's firewall, and forward it on your router if
  the agent is outside your network.

Whichever mode it is in, requests carrying a cross-origin `Origin` header are refused, so a web page
open in someone's browser cannot drive the server.

### Authentication

Off by default. A new server sets `requirePersonalToken` to `false`, so it works the moment it boots:
a new server is also [local only](#local-only), so reaching it already means being on the machine.
Turning it on is what matters once the endpoint is shared, whether by turning `localOnly` off or
through a tunnel or a proxy.

```json
{ "requirePersonalToken": true }
```

Which mode is active is stated at boot, since it is not obvious from anywhere else:

```
[PrefabMcp|P] Personal tokens NOT required: anyone who can reach the endpoint may use every tool.
              Set "requirePersonalToken": true in mcp.json to require one.
```

`/prefab-mcp` prints whichever configuration that server actually accepts: with the `headers` block
when a token is needed, without it when one is not, so there is never anything to delete from what it
gives you. While tokens are off, `tokenSecret` sits unused but is still generated and kept, so
switching over later does not change anyone's token.

When `requirePersonalToken` is `true`, every MCP request must carry the personal token of a player who
holds the permission below:

```
Authorization: Bearer <player-uuid>.<signature>
```

A token is **derived, not stored**: it is the player's id alongside an HMAC-SHA256 of that id under
the server's `tokenSecret`. Nothing is persisted per player, the same player is always shown the same
token, and the id travels inside the token so it can be checked on its own rather than by searching
every player the server has ever seen.

The two halves are checked separately. The token says *which player*; the permission system says
*whether they may*. So revoking someone's permission cuts off their MCP access immediately, with no
need to rotate the secret or reissue anyone else's token. Permissions resolve through groups and
wildcards and work for offline players, so a token keeps working between sessions.

| Request | Response |
|---|---|
| Any request, while `requirePersonalToken` is `false` | `200`, anonymously |
| No `Authorization` header | `401` with a `WWW-Authenticate` challenge |
| Token that does not verify | `401` |
| Valid token, player lacks the permission | `403` |
| Valid token, player holds the permission | `200` |

Tokens are shown only by `/prefab-mcp`, only to the player who ran it, and are never logged.

**To revoke everyone at once**, change `tokenSecret` in `mcp.json` and restart; every issued token
stops verifying. **To revoke one player**, take away their permission.

### Where prefabs land

Everything this plugin writes goes under `prefabs/prefab-mcp/`, so agent-made prefabs never mix with
the ones an operator or the in-game editor made.

| Caller | Directory |
|---|---|
| Anonymous, on a server not requiring tokens | `prefabs/prefab-mcp/` |
| A player who was online at the time | `prefabs/prefab-mcp/<username>/` |
| A player who was offline at the time | `prefabs/prefab-mcp/offline-players/` |

A username only resolves for a connected player, and a token deliberately keeps working between
sessions, so the offline case is ordinary rather than an error — an agent running overnight lands
there. `render_prefab` reads from the same directory it would write to, so one player cannot render
another's prefab by guessing a name.

Usernames are reduced to a single safe path segment before use.

### Permission

`/prefab-mcp` and every MCP request require `games.crescentnetwork.prefabmcp.command`, derived from
the manifest's `Group` and `Name` so it follows them rather than drifting. The node is logged at
startup:

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
  "port": 7520,
  "tokenSecret": "rK-FDBd0xT66CRThXuR30VQDwpj6D3-iSFveyJLinJw",
  "requirePersonalToken": false,
  "localOnly": true
}
```

`localOnly` decides whether other devices can connect; see [Local only](#local-only).

`tokenSecret` is generated once with `SecureRandom` and is the salt every personal token derives
from. It is never shipped with a default, because a default would make every server's tokens
forgeable by anyone who read the source. A config missing a setting gains it on the next load, with
the rest of the file left untouched.

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

`opts` carries `{yaw, pitch}` for blocks, `{level: n}` for fluids, `{hollow: true}` for the volume
primitives. Y must be between -512 and 511, because prefabs pack Y into nine signed bits. Later
writes to a coordinate replace earlier ones.

Output is sparse: only cells the script placed are written, plus the filler cells multi-cell blocks
need, so pasting overlays terrain rather than clearing a box around the build. Hytale's paste UI has
an air-override toggle for the other case.

## Orientation

Blocks turn with `{yaw, pitch}` in degrees, each 0, 90, 180 or 270:

```js
block(0, 5, 0, 'Rock_Stone_Brick_Roof_Shallow', { yaw: 90 });   // rises toward the west
block(0, 0, 0, 'Build_Black_Stairs', { yaw: 180, pitch: 180 });  // upside down, facing south
block(3, 0, 0, 'Rock_Iridescent_Brick_Beam', { pitch: 90 });     // a beam laid on its side
```

Yaw turns a block about the vertical axis the same way Hytale turns its hitbox: whatever faces north
(-Z) at yaw 0 faces west (-X) at yaw 90. Yaw alone is not enough. A survey of the block assets'
`VariantRotation` found 521 yaw-only blocks, but also 236 roofs and stairs that flip upside down
with pitch 180, 128 half slabs that stand on end with pitch 90, and 58 pipes and beams laid down
with pitch 90. Roll is not exposed: only a single test block uses it.

Each block allows only its own asset family's rotations. Asking for anything else fails the build
with the valid combinations, rather than being snapped silently the way the game snaps a
placement. `get_block_texture` lists them. The mirror helpers turn blocks as well as move them,
through the same `BlockRotationUtil.getFlipped` the builder tools use, so a mirrored roof faces the
other way.

## Multi-cell blocks

137 of the 244 hitbox assets reach past their own cell: shallow roofs (1x1x2), steep roofs (1x2x1),
beds (up to 3x3x4), doors, benches, large paintings. Hytale takes a block's footprint from its hitbox,
not its model, and fills the extra cells with filler that points back at the base cell.

- `search_blocks` has a `cells` column, e.g. `1x1x2`, and `get_block_texture` lists exactly which
  offsets a block fills at each rotation, along with how far its model really reaches.
- `build_prefab` fails a build in which a footprint overlaps another block or another footprint,
  naming the cells. Fluids may share a footprint.
- The writer adds the filler cells with the server's own `BlockSelection.tryFixFiller`. Paste copies
  cells as stored and never creates filler itself, and Hytale's prefab validator reports a multi-cell
  block without its filler as broken.

## Rendering

Hytale renders blocks on the client, so there is no server-side renderer to borrow. `render_prefab` is
a plain raycaster: one ray per pixel, marched through the voxel grid. Cubic blocks sample their real
face texture on the face the ray entered.

Model-drawn blocks are traced against their own `.blockymodel` geometry, turned to the rotation they
were placed with. All 1,151 shipped block models are built from two shape types, boxes and quads, so a
ray is tested against each shape in its own frame. A model is indexed in every cell its geometry
reaches, so overhangs and multi-cell blocks are found wherever the ray first meets them. Textures are
sampled with their alpha cut out, so a plant's crossed quads read as leaves rather than two squares.

The node transform follows the server's `BlockyModelBoundsParser`, which says it mirrors the client.
Face texture layout (offset, mirror, angle) follows the Hytale Blockbench plugin's importer, the only
public reference. Across the shipped models that rule puts 40,545 of 40,600 face rectangles inside
their textures; the rest are off by a texel at an edge. Renders of sample blocks match the game's own
inventory icons. There is still no shadowing, light level or biome tint, so grass and leaves whose
textures are greyscale until tinted render grey.

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

No server required: the script engine and geometry run against a stand-in palette, and model tests
build small models and textures in memory. Set `MCP_RENDER_DUMP=<dir>` to also write sample renders
for eyeballing.

Set `MCP_ASSETS_DIR` to a copy of the assets' `Common` folder to go further: every shipped model is
parsed and baked at every rotation, and the render dump draws real roofs, stairs, beds, plants and a
small house, each block beside the game's own inventory icon for comparison.
