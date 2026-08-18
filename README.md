# orknux-cli

[![build](https://github.com/michjak-szymanski/orknux-cli/actions/workflows/build.yml/badge.svg)](https://github.com/michjak-szymanski/orknux-cli/actions/workflows/build.yml)
[![tests](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmichjak-szymanski%2Forknux-cli%2Fbadges%2Ftests.json)](https://github.com/michjak-szymanski/orknux-cli/actions/workflows/build.yml)
[![licence: AGPL v3 or later](https://img.shields.io/badge/licence-AGPL--3.0--or--later-blue)](LICENSE)

`orkx`, the command line client for
[orknux-server](https://github.com/michjak-szymanski/orknux-server).

Orknux is an open source, workspace based agent orchestration platform: workflows built out of
agents and models, run against the things a team already uses — Slack, Jira, GitHub, Teams —
with the credentials, the runs and the audit trail kept in one place. This is that from a
terminal. Sign in, pick a workspace, start a workflow and read what it did, talk to an agent,
load a plugin, ask whether the server is configured the way it believes it is.

What the platform is and how it is put together is documented in
[orknux-server](https://github.com/michjak-szymanski/orknux-server); this repository is only
the client.

```
orkx help                                    # the commands, and which version this is
orkx help chat                               # one command's own help

orkx login
orkx workspace list
orkx workspace use 1
orkx execution list
orkx execution get 42
```

## Completion

```
orkx completion bash       >> ~/.bashrc          # or a file it sources
orkx completion powershell | Out-String | Invoke-Expression
```

Printed rather than installed: where a shell keeps its completions is the shell's business,
and a CLI that writes into a profile uninvited is one that has to be forgiven. Both scripts are
generated from the live command tree, so a command that exists is a command that completes.

Bash is picocli's own — the same tree the parser uses — and zsh reads it after
`autoload -U +X bashcompinit && bashcompinit`. PowerShell picocli does not do, so that one is
generated here, which is the shell this is mostly used from on Windows.

One limit worth knowing: **Windows PowerShell does not call a native completer when the word is
a bare `-` or `--`**, so options complete after a space, or from a letter or two — `--l` finds
`--limit` — but not from the dashes alone. Bash has no such limit. That is the shell's
behaviour, not the script's; a completer that returns something unconditionally is ignored the
same way.

## Building

```
install.cmd                      # build if needed, install, put orkx on PATH
./install.sh                     #   … the same, from a POSIX shell

./mvnw package                   # target/orkx.jar — runs on any JDK 25
native.cmd                       # target/orkx.exe — the native binary
./native.sh                      #   … the same, from a POSIX shell
./mvnw test                      # the suite
```

Every push runs the suite on **Linux and Windows**, and builds the native image — Windows is in
the matrix rather than an afterthought, because nearly every bug this CLI has had was one: a
batch file with the wrong line endings, PowerShell prefixing piped input with a byte order mark,
PATH rebuilt instead of appended to, a console calling itself a terminal when it was a pipe.

`native.cmd` and `native.sh` are two lines of work each: they find a GraalVM and run
`./mvnw -Pnative package` with `GRAALVM_HOME` pointed at it, so it is not something to
keep in your environment. They look at `$GRAALVM_HOME` first if you have set it, then
for the newest `~/.jdks/graalvm*` (and `~/.sdkman/candidates/java/*graal*` on Unix), and
say how to install one if there is none. Arguments are passed through:
`native.cmd -DskipTests`.

Built and verified here against **GraalVM CE 25.2.4 (JDK 25.0.4)**. On Windows the
image also needs the MSVC C++ tools and a Windows SDK, which `native-image` locates by
itself. A plain `./mvnw package` needs none of that, so day-to-day work does not either.

## Installing

```
install.cmd                      # -> %LOCALAPPDATA%\Programs\orkx, added to user PATH
install.cmd -Uninstall           # removes both again
install.cmd -NoPathChange        # install the binary, leave PATH alone

./install.sh                     # -> ~/.local/bin
./install.sh --prefix DIR        # somewhere else
./install.sh --uninstall
```

Per-user throughout: no administrator rights, and the machine-wide PATH is never
touched. Both build the binary first if it is not there yet, and running either twice is
harmless.

**Then open a new terminal.** A PATH change does not reach a shell that is already open,
nor a new one started from it, which inherits that shell's environment — so `orkx` will
still be "not recognized" in the window you installed from. Either open one from the
Start menu, or paste what the installer prints to fix the shell you are in:

```
$env:PATH += ";$env:LOCALAPPDATA\Programs\orkx"
```

`install.sh` prints the line to add to your profile rather than editing it, since
`~/.local/bin` is often on PATH already and a shell profile is nobody else's to write to.

Picocli's annotation processor writes the reflection metadata at compile time and JSON
goes through kotlinx.serialization, which needs no reflection at all — so the only
hand-written GraalVM metadata in the repository is one file naming the version resource.

The binary is worth the trouble for a command run this often:

| | startup | size |
|---|---|---|
| `orkx.exe` | 14 ms | 26 MB |
| `java -jar orkx.jar` | 146 ms | 3 MB + a JDK |

Running the jar instead:

```
java -jar target/orkx.jar login
```

## Choosing a server

```
orkx server use http://localhost:8080         # point orkx at an installation
orkx server info                              # where it is pointed, and as whom
```

`server use` checks the address rather than merely writing it down: `GET /api/session`
answers 401 on a working orknux-server that has not been signed in to, which tells a live
installation from a typo, a stopped server, or some other web application on that port.

Moving to a different server drops the stored session, workspace included. A `JSESSIONID`
belongs to the server that issued it, so carrying one across would mean presenting one
installation's credential to another.

`server info` reports what is stored even when nothing is answering — "which server did I
leave this pointed at" is exactly the question asked when nothing works — while still
exiting 3 to say the server could not be reached.

## Signing in

```
orkx login                                   # prompts for username and password
orkx login --server https://orknux.example.com --username alice
echo "$PASSWORD" | orkx login -u alice --password-stdin
```

The server authenticates against a directory over LDAP and answers with a session
cookie. There is no token to inspect and no refresh endpoint, so `orkx login` is the
only way to get a session and re-running it is the only way to replace one — the
server's sessions live in memory with a 30-minute idle timeout, so a restart there
means signing in again here.

Which server, first hit wins:

1. `--server`
2. `$ORKNUX_SERVER_URL`
3. the server of the stored session — so a bare `orkx login` returns to where you were
4. `http://localhost:8080`

### Where the session goes

`session.json`, in the first of these that is set:

| | |
|---|---|
| `$ORKNUX_CONFIG_HOME` | wherever you point it |
| `$XDG_CONFIG_HOME` | `$XDG_CONFIG_HOME/orknux` |
| `%APPDATA%` (Windows) | `%APPDATA%\orknux` |
| otherwise | `~/.config/orknux` |

The cookie in that file is the whole credential, so it is written owner-only — POSIX
`0600`, or a single-entry ACL on Windows — and replaced atomically, never appended to.

## Choosing a workspace

```
orkx workspace list                          # the ones you can see, * marks the one in use
orkx workspace use 7                         # work in workspace 7 from now on
```

Nearly every operation on the server is scoped to a workspace by an explicit id, and the
server holds no notion of a current one — so the choice is the client's to keep, next to
the session. `use` checks the id against the server rather than merely writing it down, so
a typo is caught here instead of by whatever runs next; that check is also the access
check, since the server answers with the workspace only if your directory groups grant it.

A workspace that is not there and one you may not see are the same answer from the server
— `findByIdOrNull(id)?.takeIf(access::canSee)` — so `orkx` does not claim to know which.

`list` fetches every page, not the server's first twenty. An empty list is a fact about
your directory group membership rather than a failure, so it is reported and exits 0.

Signing in again keeps the workspace you were in, as long as it is the same user on the
same server; another user may not be granted it at all.

## Running a workflow

```
orkx workflow list                            # what there is, and where each last got to
orkx workflow run nightly-sync                # by name, or by either of its ids
orkx workflow run 3 --input '{"since":"yesterday"}'
```

`list` shows the definition's id, which is the one `run` takes. The assignment id is not shown:
two numbers in one table, only one of which starts anything, is how the wrong one gets used.

A workflow has **two ids and they are not interchangeable**: the one identifying its assignment
to the workspace, and the one identifying the definition that runs. Both sit in the same
listing, and `startExecution` takes the second — so `run` resolves whatever you give it through
that listing and always sends the definition's id. When one number names two different
workflows, it says so rather than picking one.

`--input` is handed to the first node as JSON, which is what a trigger would have supplied;
leaving it out hands the run nothing. `--input-stdin` reads it from a pipe. It is passed
through exactly as given — what the first node will accept is the workflow's business.

The run is recorded as `MANUAL`, because a person started it. A disabled workflow is still run —
that is how one is tested — with a note saying so.

## Looking at runs

```
orkx execution list                          # the workspace's runs, newest first
orkx execution list --limit 50               # more of them
orkx execution list --workspace 3            # somewhere else, just this once
orkx execution get 42                        # one run, its steps, and what failed
orkx execution logs 42                       # what it wrote as it went
orkx execution logs 42 --step slack          # only the lines one step wrote
orkx execution restart 42                    # run it again on the same input
```

`list` is workspace-scoped because the server's query is, so it uses the workspace from
`orkx workspace use` and takes `--workspace` to look elsewhere without changing that
choice. Runs accumulate, so it shows the newest 20 rather than fetching every page, and
says `Showing 20 of 137` when there are more — a list that stops quietly reads as if it
were all of them.

`get` needs no workspace: the server resolves the run's own. Access failure is visible
here, unlike for a workspace — a run in a workspace you may not see is refused, while a
run that does not exist is simply absent, and the two are reported differently.

`logs` shows every line the run wrote, resolving each one's node key to the step's name and
marking the run's own lines `run`. It is deliberately not a table: the message runs to the
edge of the terminal, and a message of several lines — a stack trace — is indented to line
up under the first, keeping its own indentation. `--step` matches a step's name or its key.

`restart` **starts something.** The server carries the original input over deliberately, so
the new run acts on the same event: if that workflow answered somebody, it answers them
again. It gets a fresh id and is recorded as `MANUAL` whatever fired the original. There is
no confirmation prompt — naming one run on the command line is the decision — but the
output says what started.

## Talking to a chat

```
orkx chat list                                # your chats, pinned first
orkx chat search planning                     # by name
orkx chat search gyloii --messages            # …and by what was said in them

orkx chat create --recipient model:1 --name "Planning"
orkx chat open 5                              # interactive; /exit or Ctrl+D to leave
echo "summarise last night's runs" | orkx chat open 5

orkx chat config set-name      --chat-id 5 --name "Quarterly"
orkx chat config set-recipient --chat-id 5 --recipient agent:1
orkx chat delete 5
```

`list` shows only your own chats — the server answers that query for whoever is asking, so
there is no seeing a colleague's and no flag that would let you try. `*` marks a pinned one,
which is also why it is at the top.

### Two kinds of search

Searching by name and searching what was said are different questions, and the second is a
great deal more work — so `search` matches names, and looks inside messages when asked:

```
$ orkx chat search gyloii
No chat in workspace 1 is called anything like 'gyloii'. Try --messages to look inside them.

$ orkx chat search gyloii --messages
  ID  TITLE        ANSWERED BY            LAST MESSAGE         MATCHED
  5   System Test  gemma-4-31B-it-Q5_K_M  2026-08-17 19:16:17  said
```

The `MATCHED` column says which of the two found each chat — `name`, `said`, or both — and
appears only when there were two ways to match. The UI draws the same line, with the deeper
search behind a switch. Finding nothing is an answer, so it exits 0 like any other empty list.

### What answers

`--recipient` takes **a model id or an agent id**, and those are separate catalogues with
separate id sequences — so the same number is usually both. On this installation id 1 is the
model `gemma-4-31B-it-Q5_K_M` *and* the agent `Tester`. A bare number is resolved by asking,
and when it turns out to be both, `orkx` says so rather than guessing:

```
$ orkx chat create --recipient 1
'1' is both a model and an agent: gemma-4-31B-it-Q5_K_M and Tester. Say which with model:1 or agent:1.
```

Choosing an agent brings the agent's own model with it, and choosing a bare model ends the
agent's part in the chat. That is the server's rule, applied by the server — `orkx` sends one
mutation either way rather than keeping a second copy of it.

Both options of `create` may be left out: the server calls an unnamed chat "New chat" and
gives one with no recipient the workspace's first active model.

`delete` takes the history with it, so it asks first. With nothing attached to answer, it
refuses without `--yes` — a script that deletes a conversation should have to say it meant to.

The history prints first and in the same shape as the live exchange, because the server
writes the whole answer down when the stream ends: a chat reopened tomorrow reads as it did
while it was happening.

Answers stream. This is the one part of the API that is not GraphQL — server-sent events
over `POST /api/chats/{id}/stream` — because a model composes an answer over seconds, or a
large local one over minutes, and the alternative is a blank screen for the whole of it. An
agent answers in a single piece rather than word by word: it thinks through a tool loop
first, and there is nothing worth showing until that settles.

A model that cannot answer — no credentials, a refused request, a tool that would not run —
ends the answer, not the session: the reason is printed and the chat stays open. A refusal
from the server itself (no model chosen, chat switched off) ends it, because typing again
will not help.

Chats belong to one person. The server requires the workspace to be visible *and* the chat
to be yours, answering somebody else's as though it were not there, so `orkx` does not
claim to know which of the two happened.

## Checking the installation

Two different questions, and two commands:

```
orkx admin doctor                             # is it configured correctly?
orkx admin monitoring                         # can it reach the things it needs?
```

`doctor` is the one that catches what monitoring cannot. A secret key that was never set is
validated on first use, so the server starts, every dependency answers, monitoring is entirely
green — and every credential write fails hours later with a stack trace:

```
FAIL  Secret key       Not set - every credential write will fail, and stored ones cannot be read.
FAIL  Stored secrets   4 cannot be read, because the key above is not usable.
ok    Authentication   Username and password, against the directory.
ok    Schema           At v71, with nothing failed.

2 failed, of 6 checks.
```

The verdicts are the server's own — `FAIL`, `WARN`, `ok` — and it exits **6** when anything
failed, so it works as a check. A `WARN` works but is probably not what was meant, so it is
reported and exits 0.

```
orkx admin monitoring                         # what the UI's monitoring page shows
```

Each of the platform's services, what it says about itself, and what it depends on:

```
HEALTHY  orknux-server 1.0.0-SNAPSHOT
  API, sign-in, connections and workflow runs
  Answering
  checked 2026-08-17 19:31:28

  up  Database   Answering
  up  Directory  Answering
  up  Temporal   Serving    http://localhost:8233
```

Asking performs the checks — a `SELECT 1` at the database, a listing of the directory, and a
probe of every service that can say whether it is up — so it reports what is true now and
takes as long as those checks take.

A degraded installation still answers: the component reports `DEGRADED` and names what it
could not reach, which is the case worth having rather than an error to hide. **Exit code 6**
says so without anything having to read the words, so this works as a check:

```
orkx admin monitoring > /dev/null || echo "something is unwell"
```

Administrators only, and the server is what enforces that — `orkx` does not keep a second
copy of the rule. Anyone else is refused in the server's own words, with exit 1.

## Variables

```
orkx variable list                            # every one, or --catalog for one folder's
orkx var get billing/apiKey                   # the same as --catalog billing --name apiKey
orkx var set -c billing -n apiKey -v s3cret --type secret

orkx var delete billing/apiKey                 # asks first
orkx var catalog create --name billing
orkx var catalog rename --name billing --new-name invoicing
orkx var catalog delete --name invoicing
```

`var` is the same command as `variable`.

**`--type` is `secret` or `value`**, which is what the server calls a variable's *kind*: both
are encrypted at rest, and what differs is whether the value comes back with a listing or only
when somebody asks. The server's own `type` — STRING, NUMBER, BOOLEAN — is what the value
holds; a variable created here is a STRING, and the other two are set in the UI.

`list` shows what a value holds and, for a secret, only whether anything is stored:

```
CATALOG  NAME        TYPE    HOLDS   DESCRIPTION
billing  channel     value   #ops    Where invoicing posts.
billing  apiKey      secret  set     Rotated quarterly.
deploy   signingKey  secret  not set
```

`get` prints the value on standard output and nothing else, so `KEY=$(orkx var get
billing/apiKey)` is the whole of it. Reading a **secret** goes through the server's
`revealVariable`, which is the only way one comes back and which records that somebody asked —
the note saying so goes to standard error, where a capture will not pick it up.

`set` writes whether or not the variable was there: the server has no upsert, so this looks the
name up in the catalog and creates or updates accordingly, and says which it did. It will not
invent a catalog — a typo becoming a second catalog is how they multiply — so it names the ones
that exist instead. `--value-stdin` keeps a secret out of your shell history and the process
list.

`delete` asks first, because a value can be typed again and a secret cannot — the server is the
only place it was. With nothing attached to answer, `--yes` has to be given. It is also what
makes a catalog removable at all: the server only deletes an empty one.

`catalog delete` does not ask, and does not need to: the server removes only an empty catalog
and refuses one that still holds anything, so nothing can be lost this way.

## Plugins

```
orkx plugin list                        # what is loaded, and what each one brings
orkx plugin generate                    # a starter plugin, written by this server
orkx plugin generate -o ./mine.js       # …into a file
orkx plugin load --file ./mine.js       # load it, or replace one of the same key
orkx plugin unload 4
```

A group of its own rather than a corner of `admin`, because writing a plugin is most of the
work and only the last step of it is administration. The server still requires the
administrator role for all four.

```
3  teammates  class-template.js
  API version 1  2 KB  loaded 2026-08-17 16:09:39  by alice
  teammates_isTeammate(email: string): boolean  Whether an email address belongs to a member of this workspace.
```

Functions are listed under the plugin that brings them, named as they are actually reached:
a plugin whose key is `teammates` declaring `isTeammate` provides `teammates_isTeammate`.

A plugin's **key is its identity, not its filename** — it is what the plugin calls itself, and
loading that key again replaces what is there while keeping the row's id. That is how a plugin
is iterated on, and the output says which of the two happened:

```
$ orkx plugin load --file ./demo.js
Loaded orkxsmoke as plugin 4, API version 1.
It provides:
  orkxsmoke_isTeammate
```

`load` is the one command here that sends a file — `POST /api/plugins`, multipart, since a
plugin arrives as a file rather than as a mutation. Only the file being there is checked
locally; its size, extension, encoding and whether it holds up the plugin contract are the
server's to judge, and it answers each with a sentence written for whoever chose the file:

```
$ orkx plugin load --file ./bad.js
That file is not a usable plugin: SyntaxError: plugin.mjs:1:4 Expected ; but found a
```

`generate` asks the server for the starter, rather than carrying one: the API version in it
is the one that installation runs and the value types are the ones it has, so a template can
never describe a contract different from the one that will judge it. It goes to standard
output so it composes, or to `--output PATH` — which will not write over a file that is
already there without `--force`.

`unload` asks first, since the source goes with it and the server is the only place holding
it. One inconsistency worth knowing: a plugin that is not there is a **refusal** (exit 1)
rather than a not-found (exit 5), because `unloadPlugin` throws where `deleteChat` answers
false. Sorting that out here would mean matching on the server's sentences.

## Colour

Bold headings, statuses coloured by what they mean, faint timestamps and labels.

```
orkx execution list --color never             # plain
orkx execution list --color always            # colour even into a pipe, for `less -R`
```

Styling is decoration and never information: a status is coloured *and* spelled out, so a
pipe, a log file or a colour-blind reader loses nothing.

`auto`, the default, colours only when the output is really attached to a terminal —
redirecting into a file gives plain text. `NO_COLOR`, `CLICOLOR=0` and `TERM=dumb` all turn
it off; an explicit `--color always` overrides them, because a flag you typed should beat a
variable you forgot about.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | it worked |
| 1 | the server refused you — bad credentials, a session it no longer has, or an operation it would not do |
| 2 | bad arguments |
| 3 | nothing usable at that address |
| 4 | the exchange worked; something on this machine did not |
| 5 | no such thing there, or none you may see |
| 6 | asked and answered, and something is unwell (`admin monitoring` only) |

`orkx login` also says so on stderr, while still exiting 0, when the directory grants
the user no workspace at all. That is a real state rather than a failure, and worth
hearing at login instead of from whatever command looks broken next.

## Layout

One module, and it is meant to stay small — the point is a single fast binary, so a
dependency earns its place by being needed at the wire.

| | |
|---|---|
| `Orkx.kt` | the root command, `orkx help`, the exit codes, `main` |
| `CompletionCommand.kt` | `orkx completion`, and the PowerShell script picocli does not write |
| `ServerCommand.kt` | `orkx server use` and `info` |
| `LoginCommand.kt` | `orkx login`, and the console it reads from |
| `ChatCommand.kt` | `orkx chat open`, and the loop it runs |
| `ChatListCommand.kt` | `orkx chat list` and `search`, and how a chat is shown in a row |
| `ChatManageCommand.kt` | `orkx chat create`, `delete` and `config`, and what a recipient is |
| `ChatStream.kt` | server-sent events, and the three frames a chat sends |
| `AdminCommand.kt` | `orkx admin doctor` and `monitoring`, and what each asks |
| `WorkflowCommand.kt` | `orkx workflow list` and `run`, and which of a workflow's two ids runs it |
| `VariableCommand.kt` | `orkx variable list`, `get` and `set` |
| `VariableCatalogCommand.kt` | `orkx variable catalog create`, `rename` and `delete` |
| `PluginCommand.kt` | `orkx plugin list`, `generate`, `load` and `unload` |
| `PluginClient.kt` | the template, and the one multipart upload, written by hand |
| `WorkspaceCommand.kt` | `orkx workspace list` and `use`, and the types they exchange |
| `ExecutionCommand.kt` | `orkx execution list`, `get` and `restart`, and theirs |
| `SessionClient.kt` | `POST /api/session`, and the DTOs it exchanges |
| `GraphQlClient.kt` | `POST /graphql` for everything else, and how it reports a refusal |
| `SessionStore.kt` | `session.json`, and the permissions on it |
| `Format.kt` | one table layout, one timestamp, one duration, for every command |
| `Style.kt` | colour, and when there should not be any |
| `Input.kt` | the byte order mark PowerShell adds, taken off once |
| `ServerId.kt` | why an id is checked before it is sent |

## Licence

**GNU Affero General Public License v3.0 or later** — see [LICENSE](LICENSE)
and [NOTICE](NOTICE), which carries the section 7(b) term requiring the
attribution this program prints to be preserved.

Copyright (C) 2026 Michał Szymański.
