# Lightning Fork for Android

A phone companion for a [Lightning Fork](https://github.com/paulscode/lightning-fork)
node on StartOS or Umbrel: your on-chain and Lightning balances, and two
buttons, **Send** and **Receive**. The node holds the keys and the funds; the
phone holds only a revocable key to the node's dashboard.

- **Send** to a Bitcoin address, a BIP 21 payment request (a unified one with
  a Lightning invoice or offer pays over Lightning, with on-chain as the
  alternative), a BOLT 11 invoice, or a BOLT 12 offer. Scan it or paste it.
  On-chain sends offer the Low, Medium and High rates of the Mempool app the
  dashboard uses, or the node's own estimate.
- **Pay a Bitcoin invoice** (a Lightning invoice of the original Bitcoin
  chain) through the service set up in the dashboard's settings, under
  **Paying Bitcoin invoices**: the app shows the most it can cost, the
  service's fee and how the price compares with the market, and the proof of
  payment once it is paid.
- **Receive** with an invoice (shown as a QR code and watched until it is
  paid) or the wallet's on-chain address.
- **Activity** lists recent payments in and out.

## Pairing

In the dashboard's menu choose **Mobile app**, then **Pair a phone**, and scan
the code with the app (or, on the phone itself, use **Open in the app**). The
code works once, for five minutes. The phone names itself; the dashboard lists
each paired phone with when and how it was last seen, and removing one stops
its key at once.

## How the phone reaches the node

- **At home**, over the LAN by name, then by IP, with HTTPS pinned to the
  node's root certificate. The pairing code carries that certificate's
  SHA-256; the phone accepts the certificate only if it matches.
- **Anywhere else**, over the node's onion address through an embedded Tor
  client ([Arti](https://gitlab.torproject.org/tpo/core/arti)), with HTTPS on
  the onion pinned the same way.

The way that last worked is tried first, and the LAN is looked for again
every minute while the phone is on Tor. Calls that move money carry a request
id, so a payment whose answer is lost on the way back can be asked about
again without paying twice.

On StartOS the dashboard is reached at its own interface (give it an onion
address in StartOS for use away from home); on Umbrel, at a port of its own,
7157, and an onion of its own, set up by the app.

## Security

- The device key is encrypted with a hardware-backed Keystore key usable only
  while the phone is unlocked, and is never backed up.
- An optional app lock asks for the fingerprint, face or screen lock.
- The app talks only to your node, directly on the LAN or through the Tor
  network to its onion address. The US dollar estimate comes from your node
  too, which fetches it from its price source; it can be turned off.

## Building

Needs JDK 17, the Android SDK (platform 34), the NDK, Rust with `cargo-ndk`
and the Android targets.

```
ABIS="arm64-v8a x86_64" rust/build-android.sh   # the embedded Tor client
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Releases: see [RELEASE.md](RELEASE.md).

## Licenses

Inter typeface © The Inter Project Authors, SIL Open Font License 1.1.
Arti © The Tor Project, MIT or Apache-2.0.
