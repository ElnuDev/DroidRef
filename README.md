# DroidRef

This is a free and open-source image reference board app for Android, like [PureRef](https://pureref.com) or [VizRef](https://vizref.com). It lets you keep track of a large amount of reference images on an infinite canvas you can pan and zoom. You can crop out only the parts of each image you want to focus on and send pictures from any app that has an image sharing intent.

Download the latest release [here](https://github.com/Ruin0x11/DroidRef/releases).

![Screenshot](./static/screenshot.png)

## Features

Modelled on PureRef:

- **Import** many images at once (picker multi-select, a whole folder, share from other apps, paste, or a link). New batches are packed automatically and framed on screen.
- **Arrange** optimally, by name, by order added, or randomly; **align** left/right/top/bottom, in a row or column, or stack; **normalize** height, width, scale, size or area.
- **Per image:** crop, flip, rotate, grayscale, opacity, nearest-neighbour sampling, lock, comment, send to front/back, replace, open source link, reset transform/crop.
- **Notes** and **pen drawings** that move and scale like images; **groups**.
- **Canvas:** grid (lines/dots) with snapping, grayscale view, background colour, color picker, fit all, zoom to selection, slideshow.
- **Undo/redo**, autosave, and export of images or the whole board as one PNG.
- **Themes** matching Krita's six default colour schemes (dark, darker, bright, neutral, blender, dark orange), under More › Theme.

### Gestures

| Gesture | Action |
| --- | --- |
| Tap an image | Select it; tap again to deselect |
| Long-press an image | Add it to / remove it from the selection (keep holding to drag) |
| Long-press empty canvas, then drag | Rubber-band select |
| With **Select** on: tap / drag | Add or remove images / box-select, even over images |
| Drag / pinch a selected image | Move / scale the selection (and rotate, if rotation is on) |
| Drag / pinch anywhere else | Pan / zoom the board, even over unselected images |
| Double-tap an image | Zoom to it; again to go back. Edits notes. |
| Two-finger tap / three-finger tap | Undo / redo |
| Long-press a toolbar button | Show what it does |

Only selected images respond to dragging, so a board covered wall to wall in images can still be panned and zoomed.

Everything else, including folder import and pen settings, is in the More (⋮) menu; More › Help repeats these gestures in the app. The ? in the bottom corner shows or hides the button labels.

## Development

`nix develop` gives a shell with the Android SDK, Gradle and an emulator; `nix build` produces the APK and `nix run .#emulator` boots an emulator with the app installed.

## Credits

Most of the code was adapted from https://github.com/wuapnjie/StickerView.

## License

DroidRef is licensed under the [GNU General Public License, version 3](LICENSE.md).

## Acknowledgements

DroidRef is a fork of [Ruin0x11/DroidRef](https://github.com/Ruin0x11/DroidRef). Code taken from it remains available under the following terms:

> Copyright (c) 2020-2021 Ruin0x11
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.
