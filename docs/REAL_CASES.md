# Problems real people had, replayed here

Every case below was reported by someone on the web, rebuilt in the lab with the same versions,
and is replayed by the tests. The last column is what fixed it for them; CrashSleuth is expected
to lead to the same answer on its own.

| Case | What CrashSleuth must find | Culprit | Reported at | What fixed it |
| --- | --- | --- | --- | --- |
| `jvm-deadlock-jstack` | DEADLOCK | com.example.shop | jstack of a two-thread deadlock (Java 21 on macOS), lab/fixtures/tools style program |  |

1 cases.
