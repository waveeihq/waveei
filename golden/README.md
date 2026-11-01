# Golden tests

Shared input → expected output cases for the core. They run against the Kotlin core (`./gradlew :core:test`)
and must also pass against any port of the core (for example a Swift port for iOS).

One file per area; each file is a JSON array of cases with a `name`:

| File | Function | Case fields |
|---|---|---|
| `already_in_field.json` | "Already in this field" check | `dictation`, `field`, `expected` (boolean) |
| `separator.json` | Separator before the next dictation | `before`, `separator` (`NONE`, `SPACE`, `NEW_LINE`, `NEW_PARAGRAPH`), `expected` |
