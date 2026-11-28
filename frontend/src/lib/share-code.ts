/** Mirrors the backend CodeGenerator: no 0/O/1/I/L so codes are easy to read aloud. */
export const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ'
export const CODE_LENGTH = 5

const VALID = new RegExp(`^[${CODE_ALPHABET}]{${CODE_LENGTH}}$`)

/** Uppercases and drops characters that can never be in a code (spaces, dashes, ...). */
export function cleanCodeInput(input: string): string {
  return [...input.toUpperCase()].filter((c) => CODE_ALPHABET.includes(c)).join('')
}

export function isValidCode(code: string): boolean {
  return VALID.test(code)
}

/** Reads a code from a deep link like https://dropcode.app/ABC23 */
export function codeFromPath(pathname: string): string | null {
  const candidate = pathname.replace(/^\/+|\/+$/g, '').toUpperCase()
  return isValidCode(candidate) ? candidate : null
}
