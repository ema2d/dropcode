// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n/I18nProvider'
import { CodeInput } from './CodeInput'

function setup(initialCode?: string) {
  const onComplete = vi.fn()
  render(
    <I18nProvider lang="en">
      <CodeInput initialCode={initialCode} onComplete={onComplete} />
    </I18nProvider>,
  )
  const boxes = screen.getAllByRole('textbox') as HTMLInputElement[]
  return { onComplete, boxes, user: userEvent.setup() }
}

describe('CodeInput', () => {
  it('moves to the next box while typing and reports the full code', async () => {
    const { onComplete, boxes, user } = setup()
    expect(boxes).toHaveLength(5)
    await user.type(boxes[0], 'ab')
    // two characters typed: focus is on the third box
    expect(boxes[2]).toHaveFocus()
    await user.keyboard('c23')
    expect(boxes.map((b) => b.value).join('')).toBe('ABC23')
    expect(onComplete).toHaveBeenCalledWith('ABC23')
  })

  it('ignores characters that are never in a code', async () => {
    const { boxes, user } = setup()
    await user.type(boxes[0], 'O')
    expect(boxes[0].value).toBe('')
  })

  it('fills all boxes on paste', async () => {
    const { onComplete, boxes, user } = setup()
    await user.click(boxes[0])
    await user.paste(' abc-23 ')
    expect(onComplete).toHaveBeenCalledWith('ABC23')
  })

  it('Backspace on an empty box goes back', async () => {
    const { boxes, user } = setup()
    await user.type(boxes[0], 'AB')
    await user.keyboard('{Backspace}')
    expect(boxes[1].value).toBe('')
    expect(boxes[1]).toHaveFocus()
  })

  it('prefills from a deep link', () => {
    const { boxes } = setup('XYZ23')
    expect(boxes.map((b) => b.value).join('')).toBe('XYZ23')
  })
})
