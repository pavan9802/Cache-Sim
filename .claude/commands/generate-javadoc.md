Generate Javadoc comments for unstaged Java files in the working tree.

If $ARGUMENTS specifies file paths, generate Javadoc for those files only.
If no argument is given, run `git diff --name-only` to find unstaged modified Java files,
and also include untracked Java files from `git ls-files --others --exclude-standard`.
Generate and write Javadoc directly into those files.

---

## What to Document

Add or complete Javadoc on:
- Every `public` and `protected` class, interface, record, and enum
- Every `public` and `protected` method
- Every `public` and `protected` field (unless self-evident from a `record` component)

Skip:
- `private` members unless they are complex enough to warrant explanation
- Getters/setters whose names fully describe their contract
- `@Override` methods that inherit sufficient Javadoc from the interface

---

## Javadoc Rules for This Project

**Classes and interfaces:**
- First sentence: what the class *is* (noun phrase), not what it *does*
- Second sentence (if needed): threading model — reference `@ThreadSafe` or `@NotThreadSafe`
- For cache implementations: note the eviction policy and whether TTL is supported

**Methods:**
- First sentence: what the method *returns* or *does* (active voice, no "This method...")
- `@param` for every parameter — one line, describe constraints (e.g., "must not be null", "must be > 0")
- `@return` unless `void`
- `@throws` for every checked and significant unchecked exception
- For `Cache.get()`: document null-on-miss behavior explicitly
- For lock-acquiring methods: document which lock is held and why (e.g., "Acquires write lock because accessOrder mutation is a write")

**Records:**
- Document the compact constructor if it validates invariants
- Each component gets a one-line description in the class-level Javadoc using `@param`

**Concurrency notes (MUST include when applicable):**
- If a method acquires a lock, say which one and why
- If a method is safe to call from multiple threads, say so
- If a method must NOT be called while holding a specific lock (e.g., StampedLock reentrancy), say so

---

## Style Constraints

- Use `{@code X}` for inline code references, not backticks
- Use `{@link ClassName}` for cross-references to other project classes
- Do not state the obvious: `/** Returns the key. */` on `getKey()` adds no value — skip it
- Do not restate the method signature in the Javadoc body
- First sentence must end with a period and fit on one line (Javadoc tools use it as the summary)
- No `@author` tags — this project uses git blame

---

## Output

After writing the files, print a summary:

```
FILES UPDATED
  path/to/File.java — N methods documented, N classes documented

SKIPPED (no public undocumented members)
  path/to/File.java

TOTAL
  Files modified: N
  Members documented: N
```
