# Pizza Event

## Remote stand interaction probe

`remote-cash-deposit` is a manual, one-shot probe in the **Pit** module category. It is separate from Auto Event and does not move the player.

When enabled, it searches loaded entities for a text display containing “pizza”, then finds a living stand NPC within 3 blocks of that label. It sends one right-click interaction to that NPC from the player's current location. It never attacks the NPC and never retries during the same activation.

The module reports the attempt distance. It then watches for up to 1.8 seconds for a pizza inventory increase or a chat reply containing pizza/cash/deposit/dollar. If neither appears, it reports that the interaction could not be confirmed. That result is inconclusive: the server could have banked cash without changing pizza inventory or sending a matching chat message.

## How to revisit the test

1. Join an active Pizza event and deliver at least one pizza so there is unbanked cash.
2. Stand away from the Pizza stand, but close enough that its label and NPC are loaded. Record the current deposited-cash total/placement indicator and pizza count.
3. Enable **Pit → Remote Cash Deposit** once. Record the attempt distance, any chat reply, the pizza count, and whether the deposited-cash total changed.
4. If there is no clear result, move to the stand and interact normally to establish the baseline. Repeat from a few distances, toggling the module off and on for each one-shot attempt.

Do not treat a missing pizza-count change by itself as proof of rejection. The decisive observation is whether the event's deposited-cash total changes before the player reaches the stand.

## Current understanding

The event description says delivered cash is deposited at the Pizza stand. The client-side probe deliberately sends the ordinary entity right-click packet without a local distance check; only a live event can establish whether Hypixel accepts that interaction remotely. The first live attempt produced no observed effect, which is consistent with the server rejecting out-of-range interactions, but the exact target and server response still need confirming.

Reference: [The Hypixel Pit Wiki — Pizza](https://pit.wiki/Pizza).
