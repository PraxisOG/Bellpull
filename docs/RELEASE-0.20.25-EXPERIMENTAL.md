# Bellpull 0.20.25 experimental · Finish local speech after a playback underrun

When local voice synthesis produces audio more slowly than Android plays it, Android can leave the last part of a phrase in its buffer. Bellpull now lets Android play that final audio and continue to the next phrase. On build 74 this could pause Qwen with “Audio output did not finish” after navigation returned audio focus.

This release includes Android build 75 and server plugin 0.9.22-mobile-preview. [Voice interruption tracker](https://github.com/PraxisOG/Bellpull/issues/3)
