# PrefabMcp

Hosts an [MCP](https://modelcontextprotocol.io) server inside a Hytale server so agents such as Claude Code,
Github Copilot, or Codex can author prefabs against that server's own live block palette.

<table>
  <tr>
    <td><img src="docs/media/agent.gif" height="400" alt="Claude Code building a Utah teapot prefab with the MCP tools (10x speed)"></td>
    <td><img src="docs/media/orbit.gif" height="400" alt="Orbiting the finished voxel teapot prefab"></td>
  </tr>
  <tr>
    <td align="center"><sub>An agent building the Utah teapot (10x speed)</sub></td>
    <td align="center"><sub>The finished prefab</sub></td>
  </tr>
</table>

Five tools are available: search the palette, inspect a block, run a build script, render the result, and
publish it once it is finished.

| MCP tool | Description |
| --- | --- |
| `search_blocks` | Real names from the running server, mods included, with each block's size in cells |
| `get_block_information` | Everything about a block: its face texture (or inventory icon for model-drawn blocks), size and footprint at each rotation, supported yaw/pitch, and model extent |
| `build_prefab` | Runs your JavaScript, writes `<name>.prefab.json` to a private [staging area](#where-prefabs-are-created) |
| `render_prefab` | PNG from any camera angle, so the agent can see what it built |
| `publish_prefab` | Copies a staged prefab into `prefabs/prefab-mcp/`, where players can find it |
| `search_prefabs` | Lists existing prefabs by name or path, a page at a time, with each one's size and block count, so they can be rendered or pasted with `prefab()` |

## Build and install

Requires Java 25 and Maven to build locally.

Start the server and look for:

```
[PrefabMcp|P] Palette snapshot: <your-total> blocks and fluids
[PrefabMcp|P] MCP server listening on http://127.0.0.1:<mcp-port>/mcp
```

Run `/prefab-mcp` in game and it opens a page with the configuration, and your personal token when
one is required, in fields you can select and copy. On a server that is [local only](#local-only), which
is the default, that looks like:

```
MCP server listening on port <mcp-port>
Add this to your MCP client configuration on this machine. This server is local only, so it
accepts connections from this machine alone; to use it from other devices, set "localOnly": false
in mcp.json and restart.
{
  "mcpServers": {
    "hytale-prefab": {
      "type": "http",
      "url": "http://127.0.0.1:<mcp-port>/mcp"
    }
  }
}
```

Or point an agent on the same machine at it directly:

```
claude mcp add --transport http hytale-prefab http://127.0.0.1:<mcp-port>/mcp
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
      "url": "http://<server-host>:<mcp-port>/mcp",
      "headers": {
        "Authorization": "Bearer 657827e9-8e16-4614-b533-4cd444373a46.HvjnLeoLyTwzJ-8kMPhP69Vze-7MXHZz"
      }
    }
  }
}
```

Before turning it off, it is recommended to enable [requirePersonalToken](#Authentication), as anyone
with access to the port will be able to generate prefabs, regardless of their permissions
on the server.

### Authentication

Off by default. A new server sets `requirePersonalToken` to `false`, so it works the moment it boots:
a new server is also [local only](#local-only), so reaching it already requires being on the machine.

```json
{ "requirePersonalToken": true }
```

Which mode is active is stated at boot:

```
[PrefabMcp|P] Personal tokens NOT required: anyone who can reach the endpoint may use every tool.
              Set "requirePersonalToken": true in mcp.json to require one.
```

`/prefab-mcp` prints whichever configuration that server actually accepts: with the `headers` block
when a token is needed, without it when one is not. While tokens are off, `tokenSecret` sits unused in `mcp.json`,
but is still generated and kept, so switching over later does not change anyone's token.

When `requirePersonalToken` is `true`, every MCP request must carry the personal token of a player who
holds the permission below:

```
Authorization: Bearer <player-uuid>.<signature>
```

The two halves are checked separately. The token says *which player*; the permission system says
*whether they may*. So revoking someone's permission cuts off their MCP access immediately, with no
need to rotate the secret or reissue anyone else's token. Tokens are shown only by `/prefab-mcp`,
only to the player who ran it, and are never logged.

**To revoke everyone at once**, change `tokenSecret` in `mcp.json` and restart; every issued token
stops verifying. **To revoke one player**, take away their permission.

### Where prefabs are created

`build_prefab` writes to a staging area in the plugin's data directory,
`mods/willchil_PrefabMcp/staging/`. That is outside `prefabs/`, so the agent's unfinished iterations
never show up in game. `render_prefab` reads from staging first. Only `publish_prefab` writes to
`prefabs/prefab-mcp/`, so agent-made prefabs never mix with the ones an operator or the in-game editor
made.

**The staging area is deleted when the server shuts down.** Anything the agent did not publish is lost.

Both locations are split by caller in the same way:

| Caller | Staging | Published |
|---|---|---|
| Anonymous, on a server not requiring tokens | `staging/` | `prefabs/prefab-mcp/` |
| A player who was online at the time | `staging/<username>/` | `prefabs/prefab-mcp/<username>/` |
| A player who was offline at the time | `staging/offline-players/` | `prefabs/prefab-mcp/offline-players/` |

`build_prefab` and `publish_prefab` each take `overwrite`, which replaces an existing prefab of the
same name in staging or in the published folder respectively.

`render_prefab` and the script's `prefab()` find a prefab by name in the caller's own staging, then
the caller's own published folder, then anywhere under the server's `prefabs/` directory, so prefabs
from other players, the in-game editor or an operator can be rendered and pasted too. A name with
folders in it, such as `Trees/Oak_1`, is a path inside `prefabs/` and is looked up there alone; a
plain name that matches more than one prefab fails and lists the paths to choose from. Staging stays
private: one caller can never reach another's unpublished builds.

### Permission

`/prefab-mcp` and every MCP request require `willchil.prefabmcp.command`. The node is logged at
startup:

```
[PrefabMcp|P] Registered /prefab-mcp (permission: willchil.prefabmcp.command)
```

### Configuration

On first load the plugin writes `mods/willchil_PrefabMcp/mcp.json`:

```json
{
  "port": 7520,
  "tokenSecret": "rK-FDBd0xT66CRThXuR30VQDwpj6D3-iSFveyJLinJw",
  "requirePersonalToken": false
}
```

`tokenSecret` is randomly generated on first load, and is the salt every personal token derives
from.

The port it starts with is the game's own bind port plus 2000, so several servers on one machine do
not collide.

`-Dhytale.mcp.port=<n>` overrides the file for one run without writing back to it.

If the chosen port is already taken the plugin logs a warning and stays off rather than quietly
binding somewhere else.

## Implementation details

### The block palette is live

`search_blocks` reads `BlockType.getAssetMap()` and `Fluid.getAssetMap()` from the running server,
so a block added by another mod shows up automatically and is attributed to its pack.

### Script API

The agent-facing script API is made up of plain JavaScript, with no TypeScript, modules, or I/O.

```js
block(x, y, z, name [, opts])
box(x1, y1, z1, x2, y2, z2, name [, opts])          opts {hollow: true} for a shell
line(x1, y1, z1, x2, y2, z2, name [, opts])
sphere(cx, cy, cz, r, name [, opts])
ellipsoid(cx, cy, cz, rx, ry, rz, name [, opts])
cylinder(cx, cy, cz, r, h, name [, axis] [, opts])  axis "x" | "y" (default) | "z"
prefab(name, x, y, z [, opts])                      paste a prefab by name or prefabs/ path, anchor at x, y, z

blockAt(x, y, z) / fluidAt(x, y, z)                 name placed there, or null
clear(x, y, z)                                      remove from both layers
mirrorX(p) / mirrorY(p) / mirrorZ(p)                duplicate everything across a plane
findBlocks({query, group, drawType, kind, limit})   array of names
nearestBlock('#8a8a8a')                             closest block by average colour
setAnchor(x, y, z)                                  prefab anchor, default 0,0,0
rng()                                               seeded, reproducible for a given seed
log(message)                                        returned with the result
```

`opts` carries `{yaw, pitch}` for blocks and for `prefab()`, which turns the whole prefab about its anchor, `{level: n}` for fluids, `{hollow: true}` for the volume
primitives. Y must be between -512 and 511, because prefabs pack Y into nine signed bits. Later
writes to a coordinate replace earlier ones.

There is no separate `fluid()` call. Pass a fluid name to any placement function and it lands in the
prefab's fluid layer:

### Orientation

Blocks turn with `{yaw, pitch}` in degrees, each 0, 90, 180 or 270:

```js
block(0, 5, 0, 'Rock_Stone_Brick_Roof_Shallow', { yaw: 90 });   // rises toward the west
block(0, 0, 0, 'Build_Black_Stairs', { yaw: 180, pitch: 180 });  // upside down, facing south
block(3, 0, 0, 'Rock_Iridescent_Brick_Beam', { pitch: 90 });     // a beam laid on its side
```

Yaw turns a block about the vertical axis the same way Hytale turns its hitbox: whatever faces north
(-Z) at yaw 0 faces west (-X) at yaw 90. Yaw alone is not enough.

Each block allows only its own asset family's rotations. Asking for anything else fails the build
with the valid combinations, rather than being snapped silently the way the game snaps a
placement. `get_block_information` lists them. The mirror helpers turn blocks as well as move them,
through the same `BlockRotationUtil.getFlipped` the builder tools use, so a mirrored roof faces the
other way.

### Multi-cell blocks

Hytale assets may reach past their own cell: shallow roofs (1x1x2), steep roofs (1x2x1),
beds (up to 3x3x4), doors, benches, large paintings. Hytale takes a block's footprint from its hitbox,
not its model, and fills the extra cells with filler that points back at the base cell.

- `search_blocks` has a `cells` column, e.g. `1x1x2`, and `get_block_information` lists exactly which
  offsets a block fills at each rotation, along with how far its model really reaches.
- `build_prefab` fails a build in which a footprint overlaps another block or another footprint,
  naming the cells. Fluids may share a footprint.
- The writer adds the filler cells with the server's own `BlockSelection.tryFixFiller`. Paste copies
  cells as stored and never creates filler itself, and Hytale's prefab validator reports a multi-cell
  block without its filler as broken.

### Rendering

Hytale renders blocks on the client, so there is no server-side renderer to borrow. `render_prefab` is
a plain raycaster: one ray per pixel, marched through the voxel grid. Cubic blocks sample their real
face texture on the face the ray entered.

Model-drawn blocks are traced against their own `.blockymodel` geometry, turned to the rotation they
were placed with. A model is indexed in every cell its geometry
reaches, so overhangs and multi-cell blocks are found wherever the ray first meets them. Textures are
sampled with their alpha cut out.

The node transform follows the server's `BlockyModelBoundsParser`, which says it mirrors the client.
Face texture layout (offset, mirror, angle) follows the Hytale Blockbench plugin's importer, the only
public reference. Renders of sample blocks match the game's own
inventory icons. There is still no shadowing, light level or biome tint, so grass and leaves whose
textures are greyscale until tinted render grey.

Water gets its colour from the most common `WaterTint` across the loaded environments, multiplied
into its texture average, because water ships a near-white greyscale texture and is tinted per-biome
at render time.

### Sandbox

Host access off, no host class lookup, no IO, no threads, no native access, no environment access. The
API reaches the script as polyglot proxies rather than as Java objects, so there is nothing to walk
back into the JVM through. A runaway script is stopped by cancelling its context from a watchdog,
which is the only thing that interrupts a spinning loop.

Nothing here touches the world, so no world-thread marshalling is needed. Palette snapshots are taken
under `AssetRegistry.ASSET_LOCK`.

### Tests

```
mvn test
```

No server required: the script engine and geometry run against a stand-in palette, and model tests
build small models and textures in memory. Set `MCP_RENDER_DUMP=<dir>` to also write sample renders
for eyeballing.

Set `MCP_ASSETS_DIR` to a copy of the assets' `Common` folder to go further: every shipped model is
parsed and baked at every rotation, and the render dump draws real roofs, stairs, beds, plants and a
small house, each block beside the game's own inventory icon for comparison.
