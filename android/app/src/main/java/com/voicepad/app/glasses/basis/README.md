# Basis — reverse-engineered protocol for the Even Realities G1

Vendored from [rodrigofalvarez/g1-basis-android](https://github.com/rodrigofalvarez/g1-basis-android)
(the `core` module, BSD 2-Clause, Copyright (c) 2025 Rodrigo Alvarez — see `LICENSE`),
which reverse-engineered the Bluetooth protocol the Even Realities G1 glasses
speak to the official phone app.

It was vendored rather than taken from Maven (`io.texne.g1.basis:core:1.0.1`)
because the published `core` is the only way to talk to the glasses without the
author's separate Hub app, and it needed these fixes:

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
4. **Writes on the main thread.** Upstream wrote with Nordic's blocking
   `await()`, which throws on the main thread — where VoicePad calls it — so
   every page write failed. `G1BLEManager.send` now suspends instead.
5. **Request queue.** Replaced by one request at a time per arm (`G1Device.request`,
   a mutex and a timeout), which also fixes a race between the expiry thread
   and the main thread that could resume a coroutine twice.
6. **Packets.** `protocol.kt` is now `G1Protocol`, matching Even Realities'
   demo app (github.com/even-realities/EvenDemoApp): text is cut into packets
   of 191 bytes (a full page is ~205 bytes of ASCII, ~300 of CJK, and was sent
   as one oversized write), each page gets its own sequence number, and an
   acknowledgement counts only with status `0xC9`/`0xCB` (`0xCA` is a refusal).
7. **Keep-alive.** Each arm gets the official app's `0x25` heartbeat every 8 s;
   the battery check (upstream's only keep-alive) now runs every ~30 s.
8. **Reconnecting and leaks.** A link loss reports `CONNECTING` (Android
   reconnects autoConnect links on its own) instead of `DISCONNECTED`; each
   arm's coroutines and the pair's state live in scopes cancelled on
   disconnect (upstream used `GlobalScope` and a never-shut-down thread pool
   per arm); the unenqueued `createBondInsecure()` call was dropped.

`G1Name.kt` and `G1Protocol` are ours; the rest is upstream apart from the changes above.
