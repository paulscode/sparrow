# Security Policy

This is the policy for **Sparrow (BLAKE2b)**, the fork at `paulscode/sparrow` that follows the
BLAKE2b chain. It is not upstream Sparrow's policy and these are not upstream's maintainers.

**Where the issue belongs.** If it reproduces on an upstream Sparrow build, it is upstream's, and
reporting it here first delays the fix for far more people: report it to [upstream
Sparrow](https://github.com/sparrowwallet/sparrow/security/advisories/new) instead. If it is
specific to this fork, or you are not sure, report it here.

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, report them privately using GitHub's [Security tab](https://github.com/paulscode/sparrow/security/advisories/new) for this repository. This creates a private security advisory visible only to you and the maintainers.

Sensitive reports can be encrypted to the GPG key this fork's releases are signed with, fingerprint
`FF76D4843EBD7FA06D92DC0CB8AB7B8E7E280E1A`.

### What to include

To help triage the report quickly, please include as much of the following as you can:

- The version, which About and `--version` report (for example `2.5.5-blake2b.6`), the operating system, and how it was installed (installer, package, or built from source)
- A description of the vulnerability and its impact
- Steps to reproduce, ideally with a proof of concept
- Any relevant configuration (network, wallet type, connected server or hardware wallet)

Please do not include seed words, private keys, or wallet passwords in a report. If a wallet file is necessary to reproduce, create one containing no real funds.

### What to expect

- An acknowledgement of your report, usually within a few days
- An assessment of the issue
- Updates on the advisory as the fix progresses

Fixes for serious issues are generally released as soon as they are ready, rather than being held for a scheduled release.

### Disclosure

Sparrow follows the [Bitcoin Core Security Disclosure Policy](https://bitcoincore.org/en/security-advisories/). Reports are assigned one of four severity levels, which determines when details are made public:

| Severity | Description | Disclosure                                  |
| --- | --- |---------------------------------------------|
| **Low** | Hard to exploit, with minor impact. Typically requires a non-default configuration or local access to the victim's machine. | No advisory, disclosed in the release notes |
| **Medium** | Limited in scope, or requiring special conditions to trigger. | 1 year after a release containing the fix   |
| **High** | Significant impact, and typically exploitable under a default configuration. | 1 year after a release containing the fix   |
| **Critical** | Threatens user funds at scale, such as remote theft requiring no user interaction. | Ad hoc                                      |

The delay before disclosing Medium and High severity issues exists to give users running older versions time to upgrade. Critical issues fall outside the standard policy, as they are likely to require an ad hoc process worked out at the time.

Severity is assigned by the maintainers. Reports that do not show what an attacker gains, and what access they need to gain it, are handled as ordinary bugs, through the public issue tracker where appropriate. These are not assigned a severity and no advisory is published. Being accepted for triage does not by itself mean an advisory will follow.

For Medium and High severity issues, the advisory is published once the disclosure period has elapsed. Credit is given to the reporter, in the advisory or the release notes, unless anonymity is preferred.

Please give us the opportunity to release a fix before disclosing the issue publicly. If you intend to disclose on a timeline of your own, say so in your report so it can be discussed early.

There is currently no bug bounty programme for Sparrow.

## Supported Versions

Security fixes are made against the latest release only. Users are encouraged to run the most recent version, available from this repository's [releases](https://github.com/paulscode/sparrow/releases) page. Releases of this fork are not distributed by sparrowwallet.com.

Release binaries are signed with the GPG key above. Verifying the signature on a download before installing is strongly recommended. Upstream's binaries are reproducible from source from v1.5.0 onwards and the [instructions](docs/reproducible.md) carry over, but this fork's binaries are built by GitHub Actions from the tag and reproducibility has not been verified independently.

## Scope

This policy covers this fork and its submodules, [Drongo](https://github.com/paulscode/drongo) (Bitcoin protocol and wallet primitives) and [Lark](https://github.com/paulscode/lark) (USB hardware wallet interaction), on their `blake2b` branches. Report issues in any of them here rather than on the submodule repositories, so that a fix and a release can be coordinated.

Because this build follows a chain whose replay protection is opt in, one class of issue matters here that does not upstream: anything that puts a transaction, or a user, on the chain that kept SHA256d without their choosing it. A source that silently describes the other chain is in scope even where it would be harmless upstream.

Examples of issues we are particularly interested in:

- Loss or theft of funds, including anything that causes a transaction to send to an address other than the one displayed
- Disclosure of private keys, seed words, or wallet passwords
- Remote code execution, including via wallet files, transaction files, PSBTs, descriptors, URI handling, or a malicious or compromised server
- Bypasses of wallet encryption or the passphrase and PIN prompts
- Failures in hardware wallet interaction, such as a device being asked to sign something other than what was shown to the user, or the defeat of on-device address or output verification
- Privacy leaks that link wallet addresses to a user's identity or network address beyond what is inherent to the connection type chosen

The following are generally out of scope:

- Vulnerabilities in third-party servers, hardware wallet firmware, or upstream dependencies, which should be reported to the relevant project (though we appreciate being told where Sparrow's use of them makes the impact worse)
- Attacks requiring an already compromised operating system, or physical access to an unlocked machine
- Missing hardening or best-practice recommendations without a demonstrated impact
- Reports generated by automated scanners or language models where the reporter has not independently verified the issue and demonstrated its impact. A plausible-sounding description of a vulnerability is not a vulnerability; if you cannot reproduce it, we are unlikely to be able to either
