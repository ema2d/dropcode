import { describe, expect, it } from 'vitest'
import { cleanCodeInput, codeFromPath, isValidCode } from './share-code'

describe('share codes', () => {
  it('cleans user input', () => {
    expect(cleanCodeInput(' ab-c 23 ')).toBe('ABC23')
    // Ambiguous characters are never part of a code
    expect(cleanCodeInput('O0I1L')).toBe('')
  })

  it('validates like the backend', () => {
    expect(isValidCode('ABC23')).toBe(true)
    expect(isValidCode('ABC2')).toBe(false)
    expect(isValidCode('ABCO3')).toBe(false)
  })

  it('reads deep links', () => {
    expect(codeFromPath('/abc23')).toBe('ABC23')
    expect(codeFromPath('/ABC23/')).toBe('ABC23')
    expect(codeFromPath('/')).toBeNull()
    expect(codeFromPath('/share-target')).toBeNull()
  })
})
