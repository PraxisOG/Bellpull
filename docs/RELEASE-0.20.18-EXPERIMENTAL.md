# Bellpull 0.20.18 experimental · Background answers on stock Hermes

Build 68 pairs with plugin 0.9.13-mobile-preview. On stock Hermes 0.23.4, Bellpull now shows a worker’s saved answer as **Background result** and clears the pending indicator for that worker. Reopening the chat or receiving the same completion again shows the answer once.

The plugin reads the native transcript and includes the delegation ID in its history response. If Hermes exhausts all eight delivery attempts while a chat is busy, it can still drop the result before saving a transcript row. That case remains open. [Delivery tracker](https://github.com/PraxisOG/Bellpull/issues/51)
