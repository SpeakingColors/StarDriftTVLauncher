# Make it your own: music and sounds

The ambient song and the interface sounds are plain files in `app/src/main/res/raw/`. Replace them with your own, keep the **same file names**, and build as usual (see [BUILD.md](BUILD.md)). Nothing in the code needs to change.

## Home music

| File | What it is |
|---|---|
| `stardrift_loop.pcm` | The ambient loop that plays on the home screen and in the screensaver |

The file is **raw audio with no header**: 16 bit, stereo, little endian, **14,700 Hz**. It must be longer than one second. It loops from the last sample back to the first, so make your audio end where it began, or the loop will click.

Convert any song with [ffmpeg](https://ffmpeg.org/):

```
ffmpeg -i mysong.wav -ac 2 -ar 14700 -f s16le app/src/main/res/raw/stardrift_loop.pcm
```

The low sample rate keeps the file small and gives a soft, warm sound. Music with lots of bright high frequencies loses them.

## Interface sounds

Nine short sounds, each a **WAV file, 16 bit PCM, stereo, 22,050 Hz**:

| File | Plays when |
|---|---|
| `sfx_move_h.wav` | focus moves left or right |
| `sfx_move_v.wav` | focus moves up or down |
| `sfx_select.wav` | OK is pressed |
| `sfx_back.wav` | Back is pressed |
| `sfx_open.wav` | a popup or pane opens |
| `sfx_close.wav` | a popup or pane closes |
| `sfx_bump.wav` | focus hits the edge |
| `sfx_on.wav` | a switch turns on |
| `sfx_off.wav` | a switch turns off |

Convert a sound with:

```
ffmpeg -i mysound.wav -ac 2 -ar 22050 -acodec pcm_s16le app/src/main/res/raw/sfx_select.wav
```

Keep them short (a fraction of a second) and quiet: they play often, for example while you hold a direction key. Two sounds closer than 35 ms apart are merged on purpose.

## Check it

1. Build (push to your copy, or run the workflow by hand) and install.
2. Open the speaker icon: **Music** and **Effects** both need to be On.
3. Press the arrows and OK on the home screen to hear the effects.

## Your own music is yours

The original song and sounds are under this project's license. Music and sounds you add to your own copy are yours, under whatever terms you choose.
