# Basis — reverse-engineered protocol for the Even Realities G1

Vendored from [rodrigofalvarez/g1-basis-android](https://github.com/rodrigofalvarez/g1-basis-android)
(the `core` module, BSD 2-Clause, Copyright (c) 2025 Rodrigo Alvarez — see `LICENSE`),
which reverse-engineered the Bluetooth protocol the Even Realities G1 glasses
speak to the official phone app.

It was vendored rather than taken from Maven (`io.texne.g1.basis:core:1.0.1`)
because the published `core` is the only way to talk to the glasses without the
author's separate Hub app, and it needed four fixes that only make sense here:

1. **Advertised names.** Upstream only matched `Even G1_...`, but the official
   demo app pairs `G1_<channel>_<L|R>_<serial>` (e.g. `G1_45_L_92333`). `G1Name`
   accepts both, reads the name from the advertisement (`scanRecord.deviceName`)
   rather than the cached device name, and no longer crashes on names without
   the expected underscores.
2. **Pairing.** Upstream forgot an arm seen in an earlier scan batch, so a pair
   whose two arms were reported in different batches could never complete; and
   a pair could be announced twice. Both are fixed in `G1.find`.
3. **Notifications.** The `enableNotifications` request was never enqueued, so
   the CCCD was not written and the glasses' acknowledgements never arrived.
4. **Stuck writes.** A write whose acknowledgement is lost held the command
   queue until the 15-second battery heartbeat noticed; requests are now
   expired by a one-second check instead.

`G1Name.kt` is ours; the rest is upstream apart from the changes above.

Unrelated: what the protocol does with a page of text is `0x4E`, carrying the
page number and the text (lines joined with `\n`), written to both arms.
