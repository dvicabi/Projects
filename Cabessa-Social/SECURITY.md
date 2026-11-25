# Security policy

Cabessa Social is an unofficial Android modification. The first APK is a personal test release, not an independently security-certified product. Static and build checks do not replace runtime testing.

Do not post account credentials, session tokens, private messages, unredacted logs or signing keys in any issue. Report suspected credential leakage, unexpected network requests or release-integrity issues privately to the repository owner. If a private channel is unavailable, open a minimal issue asking for one without sensitive details.

The published modification source and receipts can be inspected. The underlying proprietary Instagram APK is a separate trust dependency. Known telemetry paths are filtered, but native service traffic remains. The default build does not apply the signature-trust bypass. No external updater or Debug bridge is included in this build.

For build verification see [the build report](docs/BUILD_RESULT_HE.md) and [network policy](docs/NETWORK_POLICY.md).
