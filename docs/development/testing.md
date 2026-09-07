# Unit Testing Rules

## Test Stack

- **Framework**: `kotlin.test` (`@Test`, `assertEquals`, `assertFailsWith`, `assertTrue`, etc.)
- **Coroutines**: `kotlinx-coroutines-test` (`runTest` for suspend functions)
- **Server**: `ktor-server-test-host` (`testApplication` for REST API tests)
- **Source sets**: write tests in `commonTest` by default; use `jvmTest`/`iosTest`/`wasmJsTest`
  only for platform-specific behavior
- **No external assertion libraries** — use `kotlin.test` assertions only
- **No mocking libraries** — write hand-crafted fakes (e.g., `FakeHttpEngine`)

## What to Test

Only write tests that verify **meaningful behavior**:

- **Business logic** — computed properties, state machines, algorithms, conditional branching
- **Validation** — `require`/`check` guards, input constraints, error paths
- **Edge cases** — zero, one, negative, empty, boundary values, overflow
- **Backward compatibility** — deserializing old JSON formats without new fields
- **Non-obvious behavior** — semantics that could surprise a reader (e.g.,
  `Immediate != AfterDelay(0.seconds)`, `distinctBy` keeping first occurrence)
- **Custom serializers** — types with hand-written serialization logic (e.g., `SpeedLimit`)
- **Integration points** — verifying component wiring, delegation, fallback chains

## What NOT to Test

Do **not** write tests for things the Kotlin language or frameworks already guarantee:

- **Data class guarantees** — `equals`, `hashCode`, `copy`, `toString`, constructor storage
- **Enum guarantees** — `entries`, `valueOf`, declaration order, exhaustive `when`
- **Sealed class guarantees** — `is` type checks, subclass hierarchy
- **Value class guarantees** — equality, identity
- **Trivial default values** — testing that `val x: Int = 0` is indeed `0`
- **Constructor parameter storage** — testing that passing a value stores that value
- **Basic kotlinx.serialization round-trips** — simple encode/decode for `@Serializable` types
  with no custom serializer (the framework guarantees this)
- **Test doubles** — do not test fakes, mocks, or stubs themselves

## Guidelines

- Prefer one test class per source class, in the same package under `commonTest`
- Use `runTest` for coroutine tests
- Name tests descriptively: `functionName_condition_expectedResult`
- Keep tests focused — one assertion per logical concept
- Use `FakeHttpEngine` and similar test doubles for isolation, but don't test the doubles
- When a formula or algorithm lives in production code, test it by calling that production
  code — never reimplement the formula locally and assert against the reimplementation
