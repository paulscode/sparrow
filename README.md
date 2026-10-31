# Sparrow (BLAKE2b)

A build of [Sparrow](https://sparrowwallet.com) that follows the BLAKE2b chain, the side of the
2026 mainnet split that changed its proof of work at height 961640.

Sparrow is a modern desktop Bitcoin wallet application supporting most hardware wallets and built on common standards such as PSBT, with an emphasis on transparency and usability. This fork keeps all of that and changes what it needs to in order to follow a different chain.

> **This is not upstream Sparrow, and it is not a Sparrow release.** Upstream follows the chain that
> kept SHA256d. If that is the chain you want, download [upstream
> Sparrow](https://sparrowwallet.com/download/) instead: this build cannot see it.

## Download

Release binaries are on the [releases page](https://github.com/paulscode/sparrow/releases) of this
repository. There is no website, and these binaries are not distributed by sparrowwallet.com.

Each release has the following files, shown here for 2.5.6-blake2b.8:

| Platform | Installer | Archive |
|---|---|---|
| macOS (Apple silicon, Intel) | `Sparrow-BLAKE2b-2.5.6-blake2b.8-aarch64.dmg`, `…-x86_64.dmg` | |
| Windows | `Sparrow-BLAKE2b-2.5.6-blake2b.8.msi` | `Sparrow-BLAKE2b-2.5.6-blake2b.8.zip` |
| Linux, Debian and Ubuntu | `sparrowwallet-blake2b_2.5.6-8_amd64.deb`, `…_arm64.deb` | `sparrowwallet-blake2b-2.5.6-blake2b.8-x86_64.tar.gz`, `…-aarch64.tar.gz` |
| Linux, Fedora and RHEL | `sparrowwallet-blake2b-2.5.6-8.x86_64.rpm`, `…aarch64.rpm` | |

Sparrow Server, the terminal-only build for Linux, comes as `sparrowserver-blake2b` in the same
`.deb`, `.rpm` and `.tar.gz` forms.

The installers install beside upstream Sparrow rather than over it: the Linux packages are named
`sparrowwallet-blake2b` and `sparrowserver-blake2b` and install to their own directories under
`/opt`, and on Windows the app is installed and registered as "Sparrow BLAKE2b". On Windows, links
such as `bitcoin:` payment requests are offered to this app through Settings, Apps, Default apps,
rather than taken over from whichever app opens them now. The macOS and Windows builds are not
signed: macOS asks you to right-click the app and choose Open the first time, and Windows may show a
SmartScreen warning.

Verify them before installing:

```
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum --ignore-missing -c SHA256SUMS
```

Every release reports which build it is. Open About, or run it with `--version`.

## What this fork changes

**It follows the other chain.** Block headers above the fork are 164 bytes rather than 80, and block
hashes above it are BLAKE2b rather than SHA256d. Both are consensus, so this is a different wallet
rather than a setting.

**It refuses a server on the chain that kept SHA256d.** The two chains share a genesis block, a
network name and an address format, so a server on the wrong one passes every ordinary check, syncs
perfectly, and then shows you another chain's balances against your addresses. On connecting, this
build asks for the block header at the fork height and checks it against the block this chain mined
there.

**Sources that describe the other chain are withdrawn.** Public Electrum servers, external fee rate
sources, the shipped block explorers and the external broadcast endpoints all follow upstream's
chain. Fee rates come from your connected server or the fixed minimum, broadcasting always goes to
your connected server, and the block explorer is [mempool.guide](https://mempool.guide). A custom
explorer URL is still available. The update check is off, because its feed is upstream's.

**It reports opt-in replay protection.** Replay protection on this chain is opt in, carried by the
unified signature hash, so an ordinary transaction is valid on both chains. The transaction screen
says whether the signatures actually opt in, and says "not checked" rather than guessing when it has
no wallet to verify them against. A transaction that spends a coin only this chain has is protected whatever its
signatures carry, and both screens say so once that coin's opted-in ancestor has been verified, provided that ancestor
was signed by one of your own wallets open in the app: anyone else who holds that key could undo it.
That is how a hardware wallet whose firmware cannot opt in gets split: see
[Replay protection for a hardware wallet that can't sign the opt-in](docs/split-with-a-hardware-wallet.md).

**It stores its files separately from upstream Sparrow**, so both can be installed at once. See
[Configuration](#configuration) below.

## Building

To clone this project, use

`git clone --recursive git@github.com:paulscode/sparrow.git`

or for those without SSH credentials:

`git clone --recursive https://github.com/paulscode/sparrow.git`

In order to build, Sparrow requires Java 25 or higher to be installed. 
The release binaries are built with [Eclipse Temurin 25.0.5+8](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8).
If you are using [SDKMAN](https://sdkman.io/), you can use `sdk env install` to ensure you have the correct version.

Other packages may also be necessary to build depending on the platform. On Debian/Ubuntu systems:

`sudo apt install -y rpm fakeroot binutils`

The binaries can be built from source using

`./gradlew jpackage`

On Linux distributions without `deb` or `rpm` packaging tools installed (such as Arch), building the installers can be skipped with

`./gradlew jpackage -PskipInstallers=true`

Note that to build the Windows installer, you will need to install [WiX](https://github.com/wixtoolset/wix3/releases).

When updating to the latest HEAD

`git pull --recurse-submodules`

Upstream's release binaries are reproducible from v1.5.0 onwards (pre codesigning and installer packaging). Its
[instructions on reproducing the binaries](docs/reproducible.md) apply to this fork with these substitutions:

- Clone `https://github.com/paulscode/sparrow.git` and check out a tag of the form `v2.5.6-blake2b.7`.
- The JDK: Eclipse Temurin 25.0.2+10 for 2.5.4-blake2b.1 to 2.5.6-blake2b.6, and 25.0.5+8 from 2.5.6-blake2b.7. The doc's
  "v2.5.6 and later" means upstream's 2.5.6, which this fork's 2.5.6-blake2b.1 to .6 predate.
- The built image is `build/jpackage/Sparrow BLAKE2b`, not `build/jpackage/Sparrow`.

This fork's binaries are built by GitHub Actions from the tag, and reproducibility has not been verified independently.

## Merging upstream

Upstream changes are merged in as they are released. Two things about this fork are easy to undo by
accident while doing that, and both fail somewhere other than the merge.

**Database migrations are a shared sequence with no coordination.** Upstream's set ends at `V10`;
this fork adds `V11__UnifiedSigHash.sql` for the keystore's unified sighash mark. The first upstream
release that adds a `V11` of its own gives two files claiming version 11. Nothing conflicts, because
the filenames differ, and Flyway then refuses to resolve them: every wallet fails to open with
"Failed to open wallet file".

When that happens, **renumber upstream's incoming migration, not this fork's**. `V11` shipped in
2.5.5-blake2b.2 and is recorded as version 11 in the history of every wallet created since. Upstream's
new file has been applied by nobody using this fork, so moving it to the next free version costs
nothing.

**A migration that has already run is frozen, comments included.** Flyway checksums each file and
validates it on every open, so changing one byte of an applied migration fails every existing wallet,
not just new ones. If a change is genuinely needed it has to be a new migration with a new version.

`MigrationVersionsTest` enforces both: it fails the build on a duplicate version, and it pins every
shipped migration by content hash.

## Connecting to a server

This build has no public servers, so it needs a server of your own that follows the BLAKE2b chain.
There are two ways to connect, both under File > Settings > Server:

- **Private Electrum:** an Electrum server such as electrs or Fulcrum, backed by a BLAKE2b node.
  This is what StartOS and Umbrel provide. Enter its address and port (usually 50001 without SSL,
  or 50002 with it). An onion address works with Tor.
- **Full Node:** a BLAKE2b node directly, over its RPC interface, with `server=1` in its config.
  The defaults are a stock node's on this computer: `127.0.0.1`, the default port, and cookie
  authentication from the default data folder. A node elsewhere needs its address and a user and
  password.

Either way the server has to follow the BLAKE2b chain. A node or Electrum server set up for upstream
Sparrow follows the SHA256d chain, and this build refuses it on connecting: it reads the block
header at the fork height and checks it against the block this chain mined there. A successful
connection means that check passed.

## Running

If you prefer to run it directly from source, it can be launched from within the project directory with

`./sparrow`

Java 25 or higher must be installed. 

## Configuration

There are a number of command line options, for example to change the home folder or use testnet:

```
./sparrow -h

Usage: sparrowblake2b [options]

  Options:
    --dir, -d
      Path to Sparrow home folder
    --help, -h
      Show usage
    --level, -l
      Set log level
      Possible Values: [ERROR, WARN, INFO, DEBUG, TRACE]
    --network, -n
      Network to use
      Possible Values: [mainnet, testnet, regtest, signet, testnet4]
    --terminal, -t
      Terminal mode
      Default: false
    --version, -v
      Show version
      Default: false
```

Note that testnet currently refers to testnet3.

As a fallback, the network (mainnet, testnet, testnet4, regtest or signet) can also be set using an environment variable `SPARROW_NETWORK`. For example:

`export SPARROW_NETWORK=testnet`

A final fallback which can be useful when running the binary is to create a file called ``network-testnet`` in the home folder (see below) to configure the testnet network.

Note that if you are connecting to an Electrum server when using testnet, that server will need to be running on testnet configuration as well.

When not explicitly configured using the command line argument above, this build stores its mainnet config file, log file and wallets in a home folder location appropriate to the operating system:

| Platform | Location |
|----------| -------- |
| macOS    | ~/.sparrowblake2b |
| Linux    | ~/.sparrowblake2b |
| Windows  | %APPDATA%/Sparrowblake2b |

These are deliberately not upstream Sparrow's `~/.sparrow` and `%APPDATA%/Sparrow`. Sharing them
would mean sharing wallets across two different chains, and the header store is indexed by height
times a fixed record width that differs between the two builds, so whichever application started
last would discard and re-download the other's. Both can be installed and run at once.

Testnet3, testnet4, regtest and signet configurations (along with their wallets) are stored in subfolders to allow easy switching between networks.

On macOS and Linux, the [XDG Base Directory Specification](https://specifications.freedesktop.org/basedir-spec/latest/) is also supported. 
This is opt in: for each category below, if the corresponding directory already exists, it is used, otherwise the home folder above continues to be used. Categories are resolved independently, so files can be moved across one at a time.

| Category | Location | Contents                              |
|----------| -------- |---------------------------------------|
| Config   | `$XDG_CONFIG_HOME/sparrowblake2b` (default `~/.config/sparrowblake2b`) | `config`, `network-*` markers         |
| Data     | `$XDG_DATA_HOME/sparrowblake2b` (default `~/.local/share/sparrowblake2b`) | `wallets`, `certs`, `lark`            |
| State    | `$XDG_STATE_HOME/sparrowblake2b` (default `~/.local/state/sparrowblake2b`) | `sparrowblake2b.log`, `tor/work`, lock files |
| Cache    | `$XDG_CACHE_HOME/sparrowblake2b` (default `~/.cache/sparrowblake2b`) | `tor/cache`                           |

Specifying a home folder with the `-d` argument disables XDG resolution entirely, and stores all files in the given folder.

## Reporting Issues

Please use the [Issues](https://github.com/paulscode/sparrow/issues) tab of this repository, not
upstream Sparrow's. Upstream does not maintain this build and cannot act on its bugs.

If the problem is in Sparrow itself rather than in following this chain, it belongs
[upstream](https://github.com/sparrowwallet/sparrow/issues); please reproduce it on an upstream
build first, so the report is about something they can see.

If possible, look in the `sparrowblake2b.log` file (Help > Show Log File) for information helpful in debugging. 

## License

Sparrow is licensed under the Apache 2 software licence.

## GPG Key

This fork's release binaries are signed with Paul Lamb's GPG key:  
Fingerprint: FF76D4843EBD7FA06D92DC0CB8AB7B8E7E280E1A  
64-bit: B8AB 7B8E 7E28 0E1A

Upstream Sparrow's binaries, on [sparrowwallet.com](https://sparrowwallet.com/download/) and in the
upstream repository, are signed with [craigraw's GPG key](https://keybase.io/craigraw) instead:  
Fingerprint: D4D0D3202FC06849A257B38DE94618334C674B40  
64-bit: E946 1833 4C67 4B40

These are different keys held by different people. Nothing here is signed by craigraw, and a
signature from that key on a build claiming to follow this chain is not from this project.

## Credit

Sparrow is by [craigraw](https://github.com/craigraw), and everything this fork does well it does
because upstream built it. Upstream changes are merged in as they are released.

The unified opt-in signature hash, and the replay protection it gives, were ported from
[Shrike](https://github.com/privkeyio/shrike) and [its drongo](https://github.com/privkeyio/drongo)
under the Apache 2 licence, with thanks. The consensus rule they implement is Bitcoin Knots
[PR #357](https://github.com/bitcoinknots/bitcoin/pull/357), by the same author. The proof of work
change this build follows is Bitcoin Knots
[PR #359](https://github.com/bitcoinknots/bitcoin/pull/359).


![Yourkit](https://www.yourkit.com/images/yklogo.png)

Sparrow Wallet uses the [Yourkit Java Profiler](https://www.yourkit.com/java/profiler/) to profile and improve performance. 
YourKit supports open source projects with useful tools for monitoring and profiling Java and .NET applications.
