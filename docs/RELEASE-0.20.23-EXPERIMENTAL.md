# Bellpull 0.20.23 experimental · Voice audio focus

When navigation temporarily takes audio focus during a hands-free conversation, Bellpull keeps the microphone closed until Android returns focus. You can change the voice mode while waiting. A push-to-talk press during the interruption requires a fresh press after focus returns.

A chat run finishing or being canceled during the interruption also leaves the microphone closed. After focus returns, Bellpull follows the selected mode and resumes any remaining reply. An explicit microphone pause still requires Resume. [Voice interruption tracker](https://github.com/PraxisOG/Bellpull/issues/3)

The release includes Android build 73 and the previously published 0.9.22-mobile-preview server plugin.
