# Saifun's Map

Modern **Xaero-style Minimap & World Map** built from scratch for **GT: New Horizons 1.7.10** (2.9.0 / 2.9.1, Java 8 & Java 17–25 LWJGL3ify, Angelica-safe).

## Features

- **Unified Minimap + Fullscreen World Map**: shared async chunk scanner, topographic height shading, true modded block texture colors, biome tinting, translucent water depth, and compressed binary `512×512` region disk cache (`.smap`).
- **Modern Xaero-Style Design**: square & circular minimap (Angelica-safe depth mask, zero stencil requirement), smooth zoom & heading rotation / North-Lock, compass rim, smart **Cave Mode** (auto roof & Nether slice), and translucent info bar.
- **Unrestricted Entity Radar (Zero FairPlay)**:
  - Never suppresses players, mobs, or cave mode from server packets/MOTD.
  - Renders player skin heads (face + hat layer) & usernames, plus dynamic mob heads for vanilla and GTNH modded mobs (and optional resource-pack 2D icon overrides) with Y-height carets.
  - Optional server-side sync: works 100% client-side on any server; if installed on a server too, syncs player positions across unlimited distance.
- **Full GTNH JourneyMap Parity via Native `Navigator` Bridge** (zero mixins required):
  - **VisualProspecting**: GregTech ore veins, underground fluids, Thaumcraft nodes, search bar, depletion toggle, hover tooltips, double-click waypoints, and optional rendering right on the Minimap.
  - **ServerUtilities**: claimed & chunkloaded team chunks and interactive claiming directly on the World Map.
  - **GTNH Overlays**: exact `3×3` chunk (`48×48`) GT ore vein grid with center-chunk highlight, `16×16` chunk grid, and Slime Chunks.
- **Waypoints & One-Click Migration**:
  - Crisp 3D in-world waypoint badges (optional beacon beam), automatic death waypoints, chat sharing & parsing (JourneyMap and Xaero formats), and teleportation.
  - Built-in one-click importer for **JourneyMap (JSON)** and **legacy Xaero (txt)** waypoints.

## Default Keybinds

| Key | Action |
| :--- | :--- |
| `M` | Open Fullscreen World Map |
| `U` | Open Waypoints Manager (includes JourneyMap/Xaero Importer) |
| `B` | Quick Add Waypoint |
| `Y` | Open Saifun's Map Settings (live preview) |
| `H` | Toggle Minimap HUD |
| `=` / `-` | Minimap Zoom In / Out |

## Building

```bash
./gradlew build
```

The playable Forge 1.7.10 JAR is generated in `build/libs/saifunsmap-<version>.jar`.

## License

LGPL-3.0 (open source).
