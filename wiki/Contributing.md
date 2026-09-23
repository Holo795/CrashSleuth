# Contributing

## A log it did not understand

This is the most useful thing you can send. A log CrashSleuth says nothing about is a gap, and gaps are
what the tool is built to close.

[Open an issue](https://github.com/Holo795/CrashSleuth/issues/new) with:

- the log or crash report (the file, or a paste — whatever is easiest);
- what actually turned out to be wrong, **if you found out**;
- the link to wherever you found the answer, if there was one.

That last part is what makes a rule possible. A message without an answer can only become a guess.

## A wrong answer

Even better. If it named the wrong mod, or gave advice that did not work, say so — with the log. Several of
the tool's sharpest rules exist because an earlier version of it was confidently wrong.

## Adding a rule yourself

Most detections are declarative, in
[`core-logs/src/main/resources/crashsleuth/signatures.json`](https://github.com/Holo795/CrashSleuth/blob/main/core-logs/src/main/resources/crashsleuth/signatures.json):

```json
{
  "id": "coreprotect-could-not-start",
  "situation": "SILENT_ERROR",
  "confidence": "CERTAIN",
  "pattern": "\\[(CoreProtect)\\] CoreProtect(?: Community Edition)? was unable to start\\.",
  "culprits": [{ "group": 1, "kind": "PLUGIN" }],
  "advice": "signature.coreprotect-could-not-start.advice",
  "source": "https://github.com/PlayPro/CoreProtect/issues/476"
}
```

What is expected of a new rule:

1. **A real source.** A link to the report it comes from. Not "I have seen this before".
2. **Advice in both languages**, in `core-model/src/main/resources/crashsleuth/messages.properties` and
   `messages_fr.properties`. Written for someone who does not know what a mixin is, and saying *why*.
3. **A test on the verbatim text** of that report, in `SignatureTest.kt`. Copy the lines exactly as the
   person pasted them.
4. **`./gradlew build` still green.** The 399-case corpus will tell you if your rule changed an existing
   answer — read that carefully rather than editing the expectation.

A confidence of `CERTAIN` means the log *states* it. If you are interpreting, it is `HIGH` at most.

## Adding a case to the lab

Better than a rule: a rule with a replay. Add an entry to `lab/scenarios.json` naming the versions and the
jars from the report:

```json
{
  "platform": "paper", "minecraft": "1.21.1", "java": 21,
  "name": "real-coreprotect-database-unreachable",
  "description": "Paper 1.21.1 where CoreProtect is pointed at a database that never answers",
  "extra": [{ "url": "https://cdn.modrinth.com/…/CoreProtect-CE-24.0.jar" }],
  "mutate": [{ "write": "plugins/CoreProtect/config.yml", "text": "use-mysql: true\n…" }],
  "source": "https://github.com/PlayPro/CoreProtect/issues/476",
  "fix": "Point CoreProtect at a database that answers, or set use-mysql: false.",
  "expect": { "situation": "SILENT_ERROR", "culprit": "CoreProtect" }
}
```

Then:

```bash
python3 lab/lab.py run real-coreprotect-database-unreachable   # needs Docker
python3 lab/lab.py sources                                      # regenerates docs/REAL_CASES.md
```

If it does not reproduce, **say so in the pull request** and leave the rule on its report alone. That is a
result too, and the project keeps a list of them.

## House rules

- Comments explain *why*, not what the line does.
- No emoji in the code or in commits.
- Conventional Commits: `feat(scope): summary`, in English.
- The specification ([`docs/SPEC.fr.md`](https://github.com/Holo795/CrashSleuth/blob/main/docs/SPEC.fr.md),
  in French) is rewritten in full at every change, with its version bumped.

## Building it

JDK 21, and Docker if you want to run the lab.

```bash
./gradlew build             # everything
./gradlew :desktop:run      # the app
./gradlew :cli:installDist  # the command line
```
