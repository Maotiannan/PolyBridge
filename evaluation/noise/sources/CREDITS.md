# Public noise recordings

These two five-second WAV files are from Karol J. Piczak's **ESC-10 subset** of
[ESC-50 commit 33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6](https://github.com/karolpiczak/ESC-50/tree/33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6).
Both rows have `esc10=True` in `meta/esc50.csv`.
The ESC-10 compilation is licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
Attribution: Karol J. Piczak, ESC dataset. [Dataset license and original credits](https://github.com/karolpiczak/ESC-50/blob/33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6/LICENSE).

| Local file | Dataset file | Original creator and recording | Original license | SHA256 |
|---|---|---|---|---|
| clock.wav | 1-62849-A-38.wav | life_interconnected, [clock_tick_close.wav](https://freesound.org/people/life_interconnected/sounds/62849/) | CC0 | a78412e271e9bac017201211a97f8e67278d157f980c62a9680bdab90d444785 |
| helicopter.wav | 1-172649-A-40.wav | senorstudy, [Small Helicopter Takes Off](https://freesound.org/people/senorstudy/sounds/172649/) | CC0 | 23ad50d792907e1caa894e6e31bb623938ae0417606b830b433da11ca2ecb2f4 |

Downloaded unchanged from the pinned repository's `audio/` directory. The short
fixture generator converts these recordings to mono 16kHz PCM, loops, scales and
mixes them with bundled synthetic speech. Those changes are made by this project,
not the original authors; no endorsement is implied. Source files are test-only.

The existing rain fixture's attribution is in
[the Android rain credits](../../../app/src/androidTest/assets/rain/CREDITS.txt).
