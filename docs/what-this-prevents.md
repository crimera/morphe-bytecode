# What this prevents

Every failure below used to reach a device — as a crash, or worse, as a patch that reports `Applied`
and silently does nothing. Each one is now a `PatchException` during the patch run, naming the method
and index.


| Device-side failure | Guard |
|---|---|
| `Invalid register: v22. Must be between v0 and v15` — a 4-bit-format opcode (`iget`, `sget`, `move`, format `22c`/`12x`) handed a high register | `scratchRegister(RegisterLimit.FOUR_BIT)` allocates from the branch-following liveness search and never outside the format's bound; exhaustion names the lowest free register instead of emitting the instruction |
| Silent no-op: the hook lands before a branch label, so every path that jumps to the original instruction skips it while the patch still reports `Applied` | `insertHook` requires the branch policy whenever the insertion point carries labels — `relocateBranchTargets = true` moves them onto the injected block |
| A loop back edge running a one-time block on every iteration | `relocateBranchTargets = false` keeps those labels on the original instruction |
| Corrupted exception table or switch payload | try-block boundaries, handler labels and `fill-array-data` / `packed-switch` payload targets are never relocated; a label that is both a protected reference and a branch target fails the patch |
| `VerifyError`: `move-result` separated from its producing invoke | inserting at a `move-result*` is rejected, and a block that starts with one requires a call in front of the hook |
| `VerifyError`: a `move-result` flavour that does not match the producer's return type | `moveResult(dest, type)` consults the producer's descriptor; `resultKindMatches` exposes the check |
| `Too many registers` / invalid register on an invoke: more than five register words, or an operand above `v15` (format `35c`) | typed invokes lower themselves to `invoke-kind/range` through a contiguous scratch span; a wide argument that is not on two consecutive registers fails the patch |
| Wrong opcode flavour: `move` where `move-object`/`move-wide` is required, a `const` that overflows the chosen width, `iget` where `iget-wide` is required | the emitters choose the encoding from the operand and field types |
| `NoSuchMethodError` / `NoSuchFieldError` from a descriptor typo | `methodReference` / `fieldReference` fail on malformed descriptors at patch time |
| Branch target out of range reaching the dex writer | insertion applies the branch fixups and turns an unreachable target into a `PatchException` naming the method |

Stability here means failing *earlier*, not emitting different code: instruction counts in verified
methods are unchanged, and sampled migrations emitted slightly fewer code units (tighter
`move`/`const` encodings, `35c` instead of `3rc` for consecutive operands).

Each row is a failure class the guards reject, not a claim that it was hit in the field: the
jump-bypass, 4-bit-register and descriptor rows answer real incidents from the 12.29 port, the rest
are design-time rules pinned by the tests in `src/test/kotlin/app/crimera/bytecode/BytecodeTest.kt`.
