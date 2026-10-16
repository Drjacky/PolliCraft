# PolliCraft - Pollinations AI for Minecraft servers

[![Made with pollinations.ai](https://raw.githubusercontent.com/pollinations/pollinations/main/packages/ui/src/brand/badge-made-with.svg)](https://pollinations.ai/?ref=badge)
[![Build](https://github.com/Drjacky/PolliCraft/actions/workflows/build.yml/badge.svg)](https://github.com/Drjacky/PolliCraft/actions/workflows/build.yml)

One plugin that adds [Pollinations](https://pollinations.ai) to your Paper, Folia or Spigot server. Every request is paid with **your own Pollen**: put your API key in the config and you are done.

**Download:** https://drjacky.github.io/PolliCraft/ (or the [latest release](https://github.com/Drjacky/PolliCraft/releases/latest))

## Features

- **Villagers that talk.** `/polli npc create Greta blacksmith` spawns a villager with its own persona. Right-click it and just type in chat. It remembers each player's conversation, even after a restart (saved in `plugins/PolliCraft/memory/`; a new persona or `/polli npc remove` clears it), and players nearby hear its answers. Type `bye` to stop.
- **Your own personas.** Four are built in (blacksmith, librarian, farmer, guide). Add more in `config.yml`, or describe one on the spot: `/polli npc create Jack a grumpy pirate who only talks about treasure`.
- **Prompt to map.** `/polli image a cozy cabin in a snowy forest` paints the picture on a filled map. Hang it in an item frame. Maps are saved to disk and survive restarts.
- **Ask the AI.** `/polli ask how do I find diamonds?` answers in chat.
- **Any live model.** Set `text-model` and `image-model` to any name or alias from the live [text](https://gen.pollinations.ai/text/models) and [image](https://gen.pollinations.ai/image/models) model lists. `/polli models text` and `/polli models image` show them in game. A villager can have its own model (`/polli npc model <model>`). At start, the log warns if a model needs paid Pollen or is down.
- **Safe for your Pollen.** A per-player cooldown, an hourly cap per player for chat and for pictures, a token cap and permission nodes for each feature. Right-clicking a villager only opens the chat, so clicking costs nothing. Ops see what each answer cost. Villagers can't be hurt and don't wander off.
- **Paper, Folia and Spigot.** All HTTP calls are async, and results are applied on the right thread (region schedulers on Folia).

## Install

1. Download `PolliCraft-<version>.jar` from the [download page](https://drjacky.github.io/PolliCraft/) and put it in your server's `plugins/` folder.
2. Start the server once. This creates `plugins/PolliCraft/config.yml`.
3. Create an API key at [enter.pollinations.ai/keys](https://enter.pollinations.ai/keys) and paste it into `config.yml`:
   ```yaml
   api-key: "sk_..."
   ```
   (Or leave it empty and set the `POLLINATIONS_API_KEY` environment variable.)
4. Run `/polli reload` (or restart). Try `/polli ask hello`.

Requirements: Java 21 and Minecraft 1.20.6 or newer (Paper, Folia, Purpur or Spigot). Tested on Paper 1.21.11.

## Commands

| Command | What it does | Permission (default) |
| --- | --- | --- |
| `/polli ask <question>` | Answer a question in chat | `pollicraft.ask` (everyone) |
| `/polli image <prompt>` | Paint a prompt on a map | `pollicraft.image` (everyone) |
| right-click an AI villager | Talk to it; type `bye` to stop | `pollicraft.talk` (everyone) |
| `/polli npc create <name> [persona or description]` | Spawn an AI villager where you stand | `pollicraft.npc` (op) |
| `/polli npc persona <persona or description>` | Change the nearest AI villager's persona (it forgets old talks) | `pollicraft.npc` (op) |
| `/polli npc model <model\|default>` | Give the nearest AI villager its own text model | `pollicraft.npc` (op) |
| `/polli npc remove` | Remove the nearest AI villager | `pollicraft.npc` (op) |
| `/polli npc personas` | List the personas in `config.yml` | `pollicraft.npc` (op) |
| `/polli models [text\|image]` | List the live Pollinations models | `pollicraft.models` (op) |
| `/polli reload` | Reload `config.yml` | `pollicraft.reload` (op) |
| | Skip the cooldown and the hourly limits | `pollicraft.bypasscooldown` (op) |
| | See the Pollen cost after each answer | `pollicraft.cost` (op) |

`/pollinations` works as an alias.

## Configuration

```yaml
api-key: ""                         # sk_... from https://enter.pollinations.ai/keys
api-base: "https://gen.pollinations.ai"
text-model: "openai"                # any text model name or alias
image-model: "flux"                 # any image model name or alias
image-size: 512                     # requested size, scaled to a 128x128 map
cooldown-seconds: 5                 # per player, between AI requests
chat-limit-per-hour: 60             # per player: /polli ask + villager chat (0 = no limit)
image-limit-per-hour: 10            # per player: /polli image (0 = no limit)
max-tokens: 300
timeout-seconds: 90

npc:
  freeze: true                      # villagers stand still
  invulnerable: true
  talk-distance: 10                 # walk further away and the chat ends
  idle-timeout-seconds: 120
  history-size: 12                  # messages each villager remembers per player (kept across restarts)
  hear-radius: 16                   # nearby players see the answers
  default-persona: "blacksmith"

personas:
  blacksmith: "You are {name}, a gruff but kind village blacksmith ..."
  # add your own; {name} becomes the villager's name
```

The full file with comments is in [`src/main/resources/config.yml`](src/main/resources/config.yml).

## How it uses Pollinations

- Text: `POST https://gen.pollinations.ai/v1/chat/completions` (OpenAI-compatible) with the villager's persona as the system prompt and the recent conversation.
- Images: `GET https://gen.pollinations.ai/image/{prompt}?model=...&width=...&height=...`, centre-cropped and scaled to one 128x128 map.
- Models: `GET /text/models` and `GET /image/models`.

All calls send `Authorization: Bearer <your key>`, so usage is billed to your Pollen.

## Build from source

```bash
./gradlew build          # runs the unit tests and builds build/libs/PolliCraft-<version>.jar
```

## License

MIT
