# Bellpull 0.20.15 experimental

Android build 65 and plugin 0.9.10-mobile-preview add reviewed retries for Bot Chat deliveries. Open **Today → Inbox → Bot Chat delivery history → Review delivery**. A failed, ambiguous or cancelled receipt can start one new attempt using the retained result. When the original receipt covers a timeout notice, choose the saved answer first. The scheduled job and original receipt keep their identities. Confirmed retry receipts are available in Inbox offline.

A queued or claimed delivery still awaits a terminal outcome. If a request loses its acknowledgement, refresh its status to check for a native receipt. The app also saves a completed chat’s “Needs review” decision before making the composer available again.

Install the matching plugin through the usual gateway plugin workflow. Hosts with the supported native Bot Chat API expose the new control.
