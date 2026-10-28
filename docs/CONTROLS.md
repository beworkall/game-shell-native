# Controls

## Touch layouts (`.icp`)
Game Shell Native uses Winlator's input-control profile format, so layouts can be shared with Winlator.

Bundled layouts: **FPS**, **Virtual Gamepad**, **GTA 5 (Winlator)**, **RTS**, **Template (12 buttons)**, plus the
runtime's **Original built-in** layout.

### Element types
| Type | What it does |
|---|---|
| Button | One or more keys; circle, rect, rounded rect or square; optional toggle, icon and custom text |
| D-pad | Four directions (default WASD) |
| Stick | Analog stick (default left thumb) |
| Trackpad | Drag to look (default right thumb) |
| Radial menu | 2 to 8 slots around a centre |
| Range button | A scrollable strip of keys: A–Z, 0–9, F1–F12 or Numpad 0–9; horizontal or vertical |

### Bindings
Every slot can be a **Keyboard**, **Mouse** or **Gamepad** binding:
- **Keyboard:** arrows, Enter, Esc, Backspace, Delete, Insert, Tab, Space, Ctrl / Shift / Alt (left and right), Home,
  End, Page Up / Down, Caps / Num Lock, 0–9, A–Z, punctuation, F1–F12, numpad.
- **Mouse:** left / middle / right button, move up / down / left / right (look), scroll up / down.
- **Gamepad:** A, B, X, Y, L1, R1, L2, R2, L3, R3, Start, Select, both thumbsticks (4 directions each), D-pad.

### Editor
Open **Controls → Edit** (or **New layout**).
- **Toolbox** (drag it by its handle): add element, delete, element settings, layout settings, done.
- Tap to select, drag to move; elements snap to the grid.
- Element settings: type, shape, bindings (with "Add another key"), scale 50–300 %, opacity, toggle, mouse-move mode,
  text, icon, range, orientation, radial slots.
- Layout settings: rename, cursor speed, overall opacity, export to Downloads, delete.
- Everything saves automatically.

## Gamepad
USB and Bluetooth controllers work as soon as they're connected. **Settings → Gamepad** has the stick deadzone,
right-stick look speed and a full button remap.

## Keyboard
In-game menu → **Keyboard** opens an on-screen keyboard that types into the game (text boxes, cheat console).
Hardware keyboards work too.

## In-game menu
**Back** gesture or the ☰ button: resume, restart, exit, HUD (off / FPS / detailed + graph), switch layout live,
edit layout, keyboard, mute, graphics (apply and restart), clear cache.
