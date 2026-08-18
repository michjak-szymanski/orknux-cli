# Working in orknux-cli

Notes for anyone — human or agent — changing this repository. See [README.md](README.md)
for what `orkx` is and how to run it.

## Commands

```
./mvnw test                                  # the suite, and what CI runs
git tag v1.2.3 && git push origin v1.2.3     # builds the three binaries, drafts a release
./mvnw package                               # target/orkx.jar
native.cmd  /  ./native.sh                   # target/orkx[.exe], needs GraalVM
install.cmd /  ./install.sh                  # that binary, onto PATH
./mvnw test -Dtest=LoginCommandTest          # one class
```

## The server this talks to

[orknux-server](https://github.com/michjak-szymanski/orknux-server) authenticates over LDAP and
answers with a session cookie. Worth knowing before adding a command:

- **There is no bearer token and no refresh endpoint.** The cookie is the credential;
  the only way to renew it is another login. `GET /api/session` is the probe for
  whether a stored one is still good.
- **Never look for the session cookie by name.** It was `JSESSIONID` under Tomcat and became
  `SESSION` the day Spring Session was added, which broke every command until the client
  stopped naming it. `SessionClient` keeps every cookie the sign-in set and sends them all
  back, the way a browser would — which also covers the CSRF token still to come.
- **Sessions are Tomcat's, in memory.** No Spring Session, so a server restart forgets every
  one however long the timeout is — and the timeout is now `ORKNUX_SESSION_TIMEOUT`,
  a fortnight by default. A `401` therefore still means "sign in again", not "something is
  wrong", and commands are written to expect it.
- **REST is only `/api/session`, chat streaming, attachments and webhooks.** Everything
  else is GraphQL at `/graphql`, where a permission failure comes back as HTTP 200 with
  a `FORBIDDEN` error in the body — so a command must read the body, not the status.
- **`GET /api/session` is how you tell an orknux-server from a wrong address.** It answers
  401 unauthenticated, which is a working installation; a 200 carrying a web page is the
  commonest way to point this at the wrong port, so `probe` treats an unreadable 200 as
  "not orknux-server" rather than letting a JSON parser error reach the terminal.
- **The chat stream is the one endpoint with its own error shape.** `ProblemDetail`, not a
  GraphQL error: 404 for a chat that is not yours, 400 for everything the caller can put
  right (no model chosen, chat switched off), and `detail` is the part written for them. A
  failure *inside* a working stream arrives as an `error` frame instead, which ends the
  answer and not the conversation — the two are different and are reported differently.
- **Workspaces are the tenancy unit, and there is no current workspace on the server.**
  `orkx workspace use` keeps the choice in `session.json`, the way the UI keeps it in local
  storage. A workspace-scoped command reads it from there and still takes `--workspace`, so
  one command can look elsewhere without disturbing the stored choice.
- **The server's ids are `Long` behind a GraphQL `ID!`.** `workspace(id: "abc")` answers
  `INTERNAL_ERROR` and a correlation number rather than a bad request, so `serverIdOrNull`
  rejects a non-numeric id before it is sent. Worth fixing on the server; until then, every
  command taking an id has the same trap.
- **Access failure is visible for some queries and not others.** `workspace(id)` hides it —
  absent and invisible are both null. `execution(id)` does not: it resolves the owning
  workspace and calls `requireWorkspaceAccess`, so a forbidden run is a `FORBIDDEN` error
  while a missing one is null. Report each as it actually is.

## Conventions

- **Every Kotlin file opens with the SPDX header, and the attribution stays in `--version`.**
  The licence is AGPL-3.0-or-later with a section 7(b) term requiring the attribution the
  program prints to be preserved, so `OrkxVersion` returning only a version would empty the
  term of its subject. New file, same four lines; see CONTRIBUTING.md.
- **One file per concern, DTOs beside the code that exchanges them.** `SessionClient.kt`
  holds the request and response types for the endpoint it calls, the way the server
  keeps its DTOs next to the controller.
- **Every line of standard input goes through `withoutByteOrderMark`.** PowerShell prefixes
  what it pipes into a native process with a UTF-8 BOM, and `U+FEFF` is a format character,
  not whitespace — so `trim()` leaves a BOM-only line looking like content. This is not
  theoretical: it put an invisible message into a real chat, because `"" | orkx chat open 5`
  did not look blank. Strip it where input is read, never at the point of use.
- **A session may be half a session.** `orkx server use` stores a server with no credentials,
  so `StoredSession.username` and `.cookie` are nullable and every command asks
  `store.read().active()`. One place decides what half a session means.
- **Everything outside the process is a field a test can replace.** `LoginCommand` has
  `console`, `store`, `clientFactory` and `env` for exactly that; the tests set them and
  run the real `CommandLine`. HTTP is not mocked — `StubSessionServer` is a real server
  on a loopback port, so the wire format is under test too.
- **A GraphQL failure is not an HTTP one.** `GraphQlClient` reads `errors[0].message` out
  of a 200 and throws `OperationRefused`; `FORBIDDEN` arrives that way. Check the body.
- **Serializers are passed in, never looked up.** `GraphQlClient.query` takes a
  `KSerializer`, so no `serializer<T>()` reflection creeps into the native image and no
  metadata is needed for a new DTO.
- **Do not claim to know what the server did not say.** `workspace(id)` is
  `findByIdOrNull(id)?.takeIf(access::canSee)`, so absent and invisible are one answer;
  the message says "no workspace N that alice can see" and stops there.
- **Subcommands are `Callable<Int>` and return an `ExitCode`.** A script needs to tell a
  refused password from an unreachable server, so those codes are part of the interface:
  documented in the README, and not to be renumbered.
- **One table, one timestamp, one duration.** `Format.kt` holds `renderTable`,
  `formatTimestamp` and `formatDuration`, so two lists cannot drift into looking like two
  programs. A marker column is a column with an empty heading.
- **Colour is decoration, never information.** A status is coloured *and* spelled out. A
  command takes its `Style` from `styleFor(spec)` at call time — not at construction, since
  `--color` is parsed after that — with a `styleOverride` for tests.
- **Measure a cell with `visibleLength`, never `String.length`.** Escape codes take no
  width; padding by length knocks every later column out of line. `FormatTest` pins this by
  asserting a coloured table equals the plain one once `stripAnsi` has run over it.
- **Escape sequences are written as `\u001B`, not as the byte.** A literal control character
  in a source file survives nothing — an editor, a re-encode, a careless `sed`.
- **`System.console() != null` is not a terminal test.** From Java 22 a console is handed
  out for redirected output too, and picocli's `Ansi.AUTO` infers support from `TERM`, so it
  says yes to a pipe under Git Bash. `Console.isTerminal()` is the real isatty and `AUTO`
  requires it, which is why `orkx execution list > runs.txt` is plain. Verified by counting
  escape bytes, not by a mock.
- **Server enums arrive as strings.** `status` and `trigger` are `String`, not Kotlin enums:
  the server already answers `SKIPPED` and `PENDING` for steps, and a CLI that cannot print
  a word until it is recompiled is worse than one that prints a word it has not seen.
- **A model id and an agent id look identical and are not.** Separate catalogues, separate
  sequences: on the development data id 1 is both `gemma-4-31B-it-Q5_K_M` and the agent
  `Tester`. `Recipients` resolves a bare number by asking both and reports the collision
  instead of guessing; `model:1` / `agent:1` say which. It asks in two requests, because
  `model(id)` and `agent(id)` return null for what is absent but *throw* for what is in a
  workspace you cannot see, and one throw in a combined document loses the other's answer.
- **Absent is not reported the same way twice.** `deleteChat` answers false for a chat that is
  not there; `unloadPlugin` throws. So `chat delete` exits 5 and `plugin unload` exits 1
  for the same situation. Both report what the server actually did — matching on its sentences
  to make the codes agree would be worse than the inconsistency.
- **`java.net.http` has no multipart.** `PluginClient` writes the body itself: boundary,
  one part named as the server's `@RequestParam`, CRLF everywhere, closing delimiter. The
  file's bytes are sent exactly as they are on disk, because the server decodes strictly as
  UTF-8 and refuses the rest — re-encoding here would turn a clear refusal into a corruption.
  `StubUploadServer` parses the body back rather than comparing strings, since a missing CRLF
  is exactly the kind of mistake a string comparison would not notice.
- **Ask before destroying, and make a script say it meant to.** `chat delete` and
  `plugin unload` prompt when something is attached and refuse without `--yes` when nothing is.
  Reserved for what cannot be undone: `execution restart` starts work and does not ask, and
  `variable catalog delete` does not either — the server removes only an empty catalog, so its
  refusal is already the guard and a prompt would be theatre.
- **A variable's `--type` is the server's `kind`.** The CLI says `secret` or `value`, which is
  what the server calls `kind`; its `type` is STRING, NUMBER or BOOLEAN. Two words for two
  things, and the CLI took the one the request used. A variable created here is a STRING.
- **A secret is read once, on purpose.** `value` is null for a `SECRET` and only
  `revealVariable` returns it, which the server records. `variable get` calls it, because
  asking is what the command is; `variable list` never does.
- **The server owns its own rules; do not keep a second copy.** `components` is
  administrators only, and `orkx admin monitoring` finds that out by asking and being refused
  in the server's words. Checking `SessionUser.admin` first would be a rule to get wrong
  twice.
- **`doctor` and `monitoring` answer different questions.** Reachability can be entirely green
  while the installation is broken: `SecretCipher` validates its key in a `by lazy { check(...) }`,
  so a missing `ORKNUX_SECRET_KEY` starts fine and fails on the first credential write. `doctor`
  is the server's own configuration verdicts; do not reimplement either one in the CLI.
- **A workflow has two ids.** `WorkspaceWorkflow.id` is the assignment to a workspace,
  `workflowId` the definition, and `startExecution` takes the second. They sit side by side in
  one listing, so `workflow run` resolves any name or number through it and sends the definition's
  id — never the number as given.
- **Completion is generated, never written by hand.** Both scripts come from the live command
  tree, so adding a command needs nothing done in `CompletionCommand`. The PowerShell one is
  ours because picocli has no PowerShell; bash is picocli's.
- **CI runs on Windows too.** Nearly every bug here has been a Windows one — CRLF batch files,
  the piped byte order mark, PATH truncation, `isTerminal`. A green Linux build proves less than
  half of this CLI.
- **An opaque server error gets one sentence of ours.** `INTERNAL_ERROR for <uuid>` says the
  server broke, not what broke; `GraphQlClient.explain` appends a pointer to `orkx admin doctor`
  without claiming to know the cause. Everything the server worded for a person is passed on
  untouched.
- **A check that has to be read is not much of a check.** `admin monitoring` exits 6 when any
  component is not `HEALTHY`, so it works in a script. No other command uses that code.
- **A list that stops says so.** `execution list` caps at `--limit` and prints
  `Showing 20 of 137`; only `workspace list` fetches every page, because workspaces do not
  accumulate and runs do.
- **A message says what happened and what to do.** Not a stack trace: the execution path
  catches `CredentialsRejected` and `ServerUnreachable` and prints their message.
- **Nothing reflective.** kotlinx.serialization for JSON, picocli's annotation processor
  for the command metadata. Both keep the native image small and buildable without
  hand-written GraalVM config — a dependency that needs reflection costs a
  `reflect-config.json` somebody has to maintain.
- **Kotlin 2.4 on Java 25 bytecode**, matching the server. The Maven wrapper is checked
  in; use `./mvnw`.

## Stack quirks worth knowing

- **kapt, not KSP.** Picocli's GraalVM metadata comes from a Java annotation processor,
  which on a Kotlin-only source tree means the `kapt` goal. It writes straight into
  `target/classes`, so no resource wiring carries it — but nothing fails loudly if the
  goal stops running either: the build passes and the binary starts refusing its own
  options. `./mvnw package` then
  `unzip -l target/orkx.jar | grep native-image` is the check.
- **Only what orkx itself reads is hand-written metadata.** That is one file,
  `src/main/resources/META-INF/native-image/io.mszymanski/orknux-cli/reachability-metadata.json`,
  for the version properties. The `native` profile carries no `-H:IncludeResources` or
  `--enable-url-protocols`; those flags are deprecated or experimental on GraalVM 25 and
  metadata is where this belongs now.
- **One build warning is upstream's.** Picocli's processor emits an empty
  `proxy-config.json`, and its presence alone earns a `DynamicProxyConfigurationResources`
  deprecation warning from native-image. Nothing here to fix; it goes when picocli moves
  to `reachability-metadata.json`.
- **Picocli scrubs interactive option values with NULs after `execute`.** The command
  clears the array itself as well, so a test asserting on the leftovers has to accept
  either.
- **Editing PATH: never `setx`, and never rebuild the string.** `setx` truncates PATH at
  1024 characters, and a developer's is routinely longer — so `install.ps1` writes it
  with `[Environment]::SetEnvironmentVariable(..., 'User')`. It also appends to and
  filters the raw string rather than splitting into a tidy list and re-joining: a PATH
  can hold empty entries, an empty entry means "the current directory", and quietly
  dropping one changes what the user's shell resolves. Only ever the User scope, so no
  administrator rights and no machine-wide damage. The test that matters is that
  install-then-uninstall leaves PATH byte-for-byte as it was.
- **`[ -f target/orkx ]` is true for `orkx.exe` on Windows.** Windows resolves a bare
  name to the executable, so `install.sh` probes `orkx.exe` first — take the other answer
  and it installs a PE file under a name no shell there will run.
- **A Maven toolchain cannot replace `GRAALVM_HOME` on Windows.** The
  native-maven-plugin does try a jdk toolchain before that variable, but Maven's
  `findTool` looks for `bin\native-image.exe` and a GraalVM ships `bin\native-image.cmd`
  — the real executable is buried in `lib\svm\bin`. Pointing a toolchain at that
  directory would work and would also tell every other plugin that `lib\svm` is a JDK.
  Hence `native.cmd` and `native.sh`, which set the variable for one build. If you touch
  `native.cmd`, keep it **CRLF and ASCII**: `cmd` misparses a batch file with LF endings,
  and does so by running the comments. `install.ps1` has to be ASCII too — Windows
  PowerShell reads a `.ps1` without a BOM as ANSI, so one em dash in a comment breaks the
  parse several lines later.
- **`System.console()` is null under a pipe**, in surefire, and in an IDE runner. The
  `Console` interface exists so prompting is testable and so a missing console produces
  "pass --password-stdin" rather than a hang.
