<!--
    The description shown on https://hub.docker.com/r/orknux/orknux-cli — kept here so it is
    versioned with the Dockerfile it describes, and so a change to how the image works and a
    change to what the image claims arrive in the same commit.

    Docker Hub does not read this file. Paste it into the repository's Description tab, and
    the one-line summary below into Short description:

      Command line client for orknux-server, the open source agent orchestration platform.

    An HTML comment so that pasting the whole file is harmless: this block does not render.
-->

# orkx

`orkx` is the command line client for **[orknux-server](https://github.com/michjak-szymanski/orknux-server)**.

Orknux is an open source, workspace based agent orchestration platform: workflows built out of agents and models, run against the things a team already uses — Slack, Jira, GitHub, Teams — with the credentials, the runs and the audit trail kept in one place. This image is that from a terminal. Sign in, pick a workspace, start a workflow and read what it did, talk to an agent, load a plugin, ask whether the server is configured the way it believes it is.

A single native binary, compiled ahead of time with GraalVM. It starts in about 14 ms, which is what makes it bearable to run a dozen times in a shell loop.

```
docker run --rm -v orkx:/config orknux/orknux-cli --version
```

## Quick start

Sign in once, then work:

```bash
docker run --rm -it -v orkx:/config \
  orknux/orknux-cli login --server https://orknux.example.com

docker run --rm -v orkx:/config orknux/orknux-cli workspace list
docker run --rm -v orkx:/config orknux/orknux-cli workspace use 1
docker run --rm -v orkx:/config orknux/orknux-cli execution list
docker run --rm -v orkx:/config orknux/orknux-cli execution get 42
```

Worth an alias, because the invocation is longer than the command:

```bash
alias orkx='docker run --rm -it -v orkx:/config orknux/orknux-cli'
orkx workflow run nightly-sync
```

## Two things that will catch you

**Mount a volume, or you will sign in every time.** `orkx` keeps its session in `session.json`, and a container without a volume is a fresh machine on every run: the login succeeds, the container exits, and the next one has never heard of it. `ORKNUX_CONFIG_HOME` is set to `/config` in the image, so mounting anything there is enough. The file holds the whole credential and is written `0600`.

**`localhost` in a container is the container.** A server running on your host is not at `http://localhost:8080` from in here:

```bash
# macOS and Windows
docker run --rm -it -v orkx:/config \
  -e ORKNUX_SERVER_URL=http://host.docker.internal:8080 \
  orknux/orknux-cli login

# Linux, where host networking is real
docker run --rm -it -v orkx:/config --network host \
  orknux/orknux-cli login
```

`ORKNUX_SERVER_URL` is read by `login`, which is where the address is chosen. Everything after it follows the session in `/config`. The image deliberately sets no default address: a default of `localhost` that means something different inside a container than everywhere else it is documented is worse than having none.

## Interactive chat

`chat open` streams an answer as the model composes it, so it wants a terminal:

```bash
docker run --rm -it -v orkx:/config orknux/orknux-cli chat open 5

# or pipe one question in
echo "summarise last night's runs" | \
  docker run --rm -i -v orkx:/config orknux/orknux-cli chat open 5
```

## It works as a check

Exit codes come through the container unchanged:

| Code | Meaning |
|---|---|
| 0 | it worked |
| 1 | the server refused you |
| 2 | bad arguments |
| 3 | nothing usable at that address |
| 4 | the exchange worked; something locally did not |
| 5 | no such thing there, or none you may see |
| 6 | asked and answered, and something is unwell |

```bash
docker run --rm -v orkx:/config orknux/orknux-cli admin monitoring > /dev/null \
  || echo "something is unwell"
```

## Tags

| Tag | What it is |
|---|---|
| `latest` | the most recent release |
| `1.2.3`, `1.2`, `1` | a specific release, and the moving major and minor |
| `edge` | the head of `main`, built on every commit |

`latest` follows releases only, never `main`: someone who types no tag should get the last thing that was released, not the last thing that was merged.

## What is inside

`linux/amd64` and `linux/arm64`, about 53 MB.

Built on `gcr.io/distroless/base-debian12` — glibc, CA certificates, and the binary. No shell and no package manager, so there is nothing in the image to run but `orkx`. It runs as uid `65532`, never as root.

Every image is built from the `Dockerfile` in the source repository, which compiles the binary itself rather than copying one in from a release — `docker build .` on a clean checkout produces the same thing, and each published tag is pulled back and run on both architectures before the build is called finished.

## Licence

**GNU Affero General Public License v3.0 or later.** The full text and `NOTICE` ship inside the image at `/usr/share/doc/orkx/`. `NOTICE` carries the section 7(b) term requiring the attribution `orkx --version` prints to be preserved.

Copyright © 2026 Michał Szymański.

- Source: <https://github.com/michjak-szymanski/orknux-cli>
- The platform: <https://github.com/michjak-szymanski/orknux-server>
- <https://orknux.ai>
