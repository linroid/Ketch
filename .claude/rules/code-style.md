# Code Style Rules

Follow the formatting rules defined in `.editorconfig`:

## General Formatting

- **Indentation**: 2 spaces (no tabs)
- **Line length**: Max 100 characters
- **Line endings**: LF (Unix-style)
- **Final newline**: Required
- **Charset**: UTF-8

## Kotlin-Specific Rules

### Imports
- No star imports (use explicit imports)
- Import order: `*`, `java.**`, `javax.**`, `kotlin.**`, `^`

### Spacing
- Space before/after colons: no space before type colon, space after type colon
- Spaces around operators:
  - Assignment operators: yes
  - Arithmetic operators (additive, multiplicative): yes
  - Logical operators: yes
  - Equality operators: yes
  - Relational operators: yes
  - Elvis operator: yes
  - Function type arrow: yes
  - When arrow: yes
  - Range operator: **no** (`0..10`, not `0 .. 10`)
  - Unary operators: **no**
- Space before parentheses: yes (for `if`, `for`, `while`, `when`, `catch`)
- Space before lambda arrow: yes
- Space after comma: yes

### Wrapping
- Method parameters: off (no wrapping by default)
- Call parameters: off (no wrapping by default)
- Method call chains: off (no wrapping by default)
- Extends list: off
- Trailing commas: yes, but only for **named** parameters/arguments spanning multiple lines
  - Named parameter declarations (`name: Type`): add trailing comma
  - Named arguments (`name = value`): add trailing comma
  - Positional arguments (no name): **no** trailing comma

### Alignment
- Align multiline parameters in declarations: yes
- Align multiline parameters in calls: no
- Align multiline binary operations: no

### Blank Lines
- Keep max 2 blank lines in code/declarations
- No blank lines after class header
- 1 blank line before declarations with comments/annotations

## Code Writing Guidelines

When writing Kotlin code for this project:

- **Public APIs**: Use explicit types and add KDoc comments
- **Internal classes**: Mark with `internal` modifier
- **Naming**: Use meaningful, descriptive names
- **Functions**: Keep focused and concise
- **Sealed hierarchies**: Use sealed classes/interfaces for closed type hierarchies (e.g., `KDownError`)
- **Simplicity**: Favor simple correctness over micro-optimizations