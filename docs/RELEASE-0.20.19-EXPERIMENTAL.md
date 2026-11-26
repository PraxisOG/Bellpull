# Bellpull 0.20.19 experimental · Coding task cancellation

Build 69 pairs with plugin 0.9.14-mobile-preview. After you use **Stop Hermes** in coding activity, the plugin checks the final native receipt for that executor. Previously a process could exit before Hermes finished confirming the stop, leaving Bellpull showing a failure. The plugin now updates the same task to **Stopped** when that confirmation arrives.

The fix also applies to previously saved activity when Hermes still has the matching receipt. [Coding activity tracker](https://github.com/PraxisOG/Bellpull/issues/43)
