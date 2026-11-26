# Bellpull 0.20.22 experimental · Phone calendar confirmation

Open **Today → Calendar** with phone calendar sharing enabled. Bellpull marks the displayed phone read as confirmed when Hermes acknowledges the same device, time, sharing policy and events. The confirmation stays with that read in the phone’s cache. Your calendar account manages synchronization with its upstream provider.

Plugin **0.9.16-mobile-preview** preserves the newest stored phone read when uploads arrive out of order. Android tells you when Hermes kept a newer read or needs a fresh read to resolve a timestamp conflict. [Reliability tracker](https://github.com/PraxisOG/Bellpull/issues/28)
