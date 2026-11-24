# Bellpull 0.20.7 experimental

Android build **57** lets you review and answer a pending Hermes approval during a voice conversation or call. The microphone stays active when the request arrives. Say **“read the approval request”** to hear the operation. After playback finishes, say **“allow this request once”** or **“deny this request.”** Bellpull honors the choices Hermes offers for that operation. You can still answer in chat or on the Today screen. This release pairs with Bellpull plugin **0.9.4-mobile-preview**. [Android change](https://github.com/PraxisOG/Bellpull/pull/75)

Other spoken follow-ups wait for the current run to finish. If the operation changes, Bellpull requires another review. The existing run-specific decision endpoint rechecks the operation before accepting the answer.

## Verification

On an API 36 emulator, on-device Moonshine Tiny recognized synthetic speech through Android microphone capture. Android eSpeak read the native request aloud. Two separate chats on a stock Hermes 0.21.5 gateway finished with the intended outcome. Each chat retained one user message and its original run. A deterministic model transport requested a harmless color confirmation and reported the actual native decision. A misrecognized review phrase left the approval pending. Physical microphone and vehicle acoustics remain field checks. [Device fixture](../tools/voice-approval-device-fixture.py) · [Approval issue](https://github.com/PraxisOG/Bellpull/issues/37)
