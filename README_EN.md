# MateSignal Unofficial

**English** | [简体中文](README.md)

MateSignal is a client-side Minecraft mod that enables cross-interaction with **[MateEngine](https://github.com/shinyflvre/Mate-Engine)**.

MateEngine is a 3D desktop avatar application. It puts a VRM or VTuber model on your desktop and supports things like dancing and sitting on window edges. With this mod installed, whatever you do in the game, the avatar has something to say about it.

Go to sleep in Minecraft and it might say *"Good night!"* on your desktop.

> **This is an unofficial port.** The original mod is by Shiny and only supports
> 1.21.x. This port brings it to 1.20.1 and 1.12.2, and provides a NeoForge build
> for 1.21.1. Not affiliated with or endorsed by the MateEngine project or the
> original author.

---

## What it does

When certain things happen in game, the avatar shows a matching speech bubble:

- Daybreak and nightfall
- A dangerous mob nearby
- Low health or hunger
- Drowning
- Death
- Rain starting
- Going to sleep
- Crafting something
- Eating
- Killing a mob
- Discovering a biome for the first time

13 event types in total. All three builds emit exactly the same set, so one MateEngine instance works with any of them.

Mob and biome names follow **the language you picked in Minecraft** — English players see English, Chinese players see Chinese. The mod itself assumes no particular language.

---

## Supported versions

| Minecraft | Loader | Java | Mod file |
|---|---|---|---|
| 1.20.1 | Forge 47.x | 17 | `matesignal-forge-1.20.1-1.1.0-1.20.1.jar` |
| 1.21.1 | NeoForge 21.1.x | 21 | `matesignal-neoforge-1.21.1-1.1.0-1.21.1.jar` |
| 1.12.2 | Forge 14.23.5.2860 | 8 | `matesignal-forge-1.12.2-1.1.0-1.12.2.jar` |

For 1.21.10 and the Forge / Fabric builds of 1.21.1, use the [original mod](https://github.com/shinyflvre/Mate-Signal).

---

## Installation

1. Install the loader for your version ([Forge](https://files.minecraftforge.net/) or [NeoForge](https://neoforged.net/))
2. Download the jar for your version from the table above
3. Drop it into your `.minecraft/mods` folder
4. Make sure MateEngine is running
5. Launch the game

This is a client-side mod. Installing it on a server does nothing, and the server does not need it.

---

## Configuration

On 1.20.1 and 1.21.1 you can use **Mods → MateSignal → Config** in game, or edit
`config/matesignal-client.toml`. On 1.12.2 the file is `config/matesignal.cfg`.

Available options:

- Mob detection radius
- Which mobs trigger the proximity warning
- Individual toggles for each of the 13 events

The mod id stays `matesignal`, so the config file is shared with the original mod and switching between them keeps your settings.

---

## Commands

| Command | What it does |
|---|---|
| `/matesignal test all` | Fires all 13 events in sequence, one per second |
| `/matesignal test <event>` | Fires one event; prefix matching works |
| `/matesignal list` | Lists every event name |
| `/matesignal reload` | Re-reads the biome name override table (1.12.2 only) |

To check whether the mod and MateEngine are talking to each other, run `/matesignal test all` and the avatar should produce a run of bubbles. You do not need to be in a world — the main menu works.

---

## Usage warning

**Do not use this on public or competitive servers.** The avatar can announce things like *"there's a creeper behind you"*, which counts as cheating on some servers. Singleplayer or a private server with friends only.

---

## Troubleshooting

Check these in order:

1. **Is MateEngine running?** The mod only sends data to localhost; with nothing listening, nothing happens
2. **Is UDP port 32145 open?** The mod sends UDP packets to `127.0.0.1:32145`; a firewall blocking that breaks it
3. **Trigger it manually with `/matesignal test all`** — bubbles means the link works and the problem is event detection; no bubbles means the link is broken
4. **Check the log** — a failed mod load leaves an error in `logs/latest.log`

---

## Building from source

Windows and PowerShell. The three platforms need different Gradle and JDK:

| Platform | Gradle | JDK |
|---|---|---|
| 1.20.1 | 8.14.3 | 17 or 21 |
| 1.21.1 | 8.14.3 | 21 |
| 1.12.2 | 4.10.3 | 8 |

```powershell
# Build all three; jars land in dist\
.\tools\build-all.ps1

# Or just one
cd versions\v1_20_1
gradle build
```

The script locates the toolchains itself and tells you which environment variable to set if it cannot find one (`GRADLE_8_HOME`, `GRADLE_LEGACY_HOME`, `JDK8_HOME`, `JDK21_HOME`). You can also run `.\setup.ps1` first to generate a `gradlew` per platform.

Technical details — repository layout, how the shared source is organised, the 8 real divergences, and what the verification checks — are in [README-DEV.md](README-DEV.md).

---

## Screenshots

![Avatar speech bubble](screenshots/01-bubble.png)

![Nightfall message](screenshots/02-night.png)

![Nearby mob warning](screenshots/03-mob.png)

![Crafting finished](screenshots/04-craft.png)

---

## Credits

- **Original mod and MateEngine**: Shiny (Johnson Jason)
  - [Mate-Signal](https://github.com/shinyflvre/Mate-Signal)
  - [Mate-Engine](https://github.com/shinyflvre/Mate-Engine)
- **Fabric port** (used as a reference): [VeridonNetzwerk](https://github.com/VeridonNetzwerk/Mate-Signal-Fabric)
- **This port**: [LilyBlack0321](https://github.com/LilyBlack0321)

The original has a small bug that never got fixed: the drowning message is sent under the event name `drowning_half`, but MateEngine only recognises `drowning`, so that message never appeared. This port fixes it, along with a false "night is coming" trigger on time jumps, English-only mob and biome names, and untranslatable biome names on 1.12.2. Full list in [README-DEV.md](README-DEV.md).

---

## License

Distributed under the **MateEngine Pro License v2.0** — full text in [LICENSE.md](LICENSE.md).

In short: this mod is free, the source is public, the original authors are credited, and it does not claim to be official. No ads and no paid content.
