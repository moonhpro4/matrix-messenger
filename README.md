# Matrix Messenger

An open-source messenger app built with Kotlin — free chat, messaging, and calling, with no phone number required. Just create an account with a username and password (or log in via a magic email link) and start messaging.

> ⚠️ **This project is in beta.** Expect bugs. Found one? [Open an issue](../../issues/new).

## Features (in progress)

- Custom account system — no phone number needed
- Add contacts by username or QR code
- Text messaging, voice messages
- Voice and video calling
- Block, mute, and report contacts
- Group chats

## Status

This is an early, actively-developed beta. Core account creation, login, contacts, messaging, and call-state logic are implemented and tested. UI, QR code scanning, group chats, and live call audio are still in progress — see open issues for what's being worked on.

## Tech stack

- Kotlin (Android)
- Cloudflare Workers KV for account/message storage
- Gmail SMTP for magic-link login emails

## Get the app

1. Download the APK from [the latest release](../../releases/latest)
2. Install it on your phone
3. Open it, create an account, and start messaging

No setup, no configuration — it just works.

## For developers

Want to build from source or contribute? The APK released above already has everything baked in via CI. If you're modifying the code yourself, secrets are pulled from GitHub Actions secrets at build time — see `.github/workflows/build.yml`.

## Contributing

Issues and pull requests are welcome. Please open an issue first for any large changes so we can discuss the approach.

## License

MIT — see [LICENSE](LICENSE).
