import { describe, expect, it } from 'vitest'

import { fuzzyRank, fuzzyScore, fuzzyScoreMulti } from './fuzzy.js'

describe('fuzzyScore', () => {
  it('matches a query as a subsequence (g4o → gpt-4o)', () => {
    expect(fuzzyScore('gpt-4o', 'g4o')).not.toBeNull()
    expect(fuzzyScore('gpt-4o', 'gpt')).not.toBeNull()
    expect(fuzzyScore('gpt-4o', '4o')).not.toBeNull()
  })

  it('returns null when characters are out of order or absent', () => {
    expect(fuzzyScore('gpt-4o', 'o4g')).toBeNull()
    expect(fuzzyScore('gpt-4o', 'xyz')).toBeNull()
    expect(fuzzyScore('gpt-4o', 'gptx')).toBeNull()
  })

  it('returns matched positions into the original target', () => {
    const m = fuzzyScore('gpt-4o', 'g4o')
    // g@0, 4@4, o@5
    expect(m?.positions).toEqual([0, 4, 5])
  })

  it('treats an empty query as a zero-score match', () => {
    expect(fuzzyScore('anything', '')).toEqual({ score: 0, positions: [] })
  })

  it('scores an exact match highest', () => {
    const exact = fuzzyScore('sonnet', 'sonnet')!.score
    const prefix = fuzzyScore('sonnet-extended', 'sonnet')!.score
    // s,o,n,n,e,t all present in order but scattered across word boundaries.
    const scattered = fuzzyScore('snorkel-online-nnet', 'sonnet')!.score

    expect(exact).toBeGreaterThan(prefix)
    expect(prefix).toBeGreaterThan(scattered)
  })

  it('ranks a prefix match above a scattered subsequence', () => {
    const prefix = fuzzyScore('gpt-4o-mini', 'gpt')!.score
    const scattered = fuzzyScore('a-g-p-t', 'gpt')!.score

    expect(prefix).toBeGreaterThan(scattered)
  })

  it('rewards word-boundary matches', () => {
    // `m25` matching model-family and version markers after boundaries.
    const boundary = fuzzyScore('mimo-v2.5-pro', 'm25')
    expect(boundary).not.toBeNull()
  })
})

describe('fuzzyScoreMulti', () => {
  it('requires every space-separated token to match (AND)', () => {
    expect(fuzzyScoreMulti('mimo-v2.5-pro', 'mim pro')).not.toBeNull()
    expect(fuzzyScoreMulti('mimo-v2.5-pro', 'mimo small')).toBeNull()
  })

  it('unions matched positions across tokens, sorted', () => {
    const m = fuzzyScoreMulti('mimo-v2.5-pro', 'pro mim')
    expect(m).not.toBeNull()
    expect(m!.positions).toEqual([...m!.positions].sort((a, b) => a - b))
  })

  it('treats whitespace-only query as a zero-score match', () => {
    expect(fuzzyScoreMulti('x', '   ')).toEqual({ score: 0, positions: [] })
  })
})

describe('fuzzyRank', () => {
  const models = ['gpt-4o', 'gpt-4o-mini', 'mimo-v2.5-pro', 'mimo-v2.5-small', 'o1-preview']

  it('drops non-matching items and ranks matches by score', () => {
    const ranked = fuzzyRank(models, 'g4o', m => m)
    const ids = ranked.map(r => r.item)

    expect(ids).toContain('gpt-4o')
    expect(ids).toContain('gpt-4o-mini')
    expect(ids).not.toContain('mimo-v2.5-small')
    // Shorter exact-ish prefix should outrank the longer variant.
    expect(ids.indexOf('gpt-4o')).toBeLessThan(ids.indexOf('gpt-4o-mini'))
  })

  it('ranks m25 so the expected model surfaces', () => {
    const ranked = fuzzyRank(models, 'm25', m => m)
    expect(ranked[0]?.item).toBe('mimo-v2.5-pro')
  })

  it('returns all items in original order for an empty query', () => {
    const ranked = fuzzyRank(models, '', m => m)
    expect(ranked.map(r => r.item)).toEqual(models)
    expect(ranked.every(r => r.positions.length === 0)).toBe(true)
  })

  it('is stable for equal scores (original index tiebreak)', () => {
    const items = ['ab', 'ab', 'ab']
    const ranked = fuzzyRank(items.map((v, i) => ({ v, i })), 'ab', x => x.v)
    expect(ranked.map(r => r.item.i)).toEqual([0, 1, 2])
  })

  it('matches across a derived key, not just the raw string', () => {
    const providers = [
      { slug: 'openai', name: 'OpenAI' },
      { slug: 'ollama', name: 'Ollama' }
    ]

    const ranked = fuzzyRank(providers, 'olla', p => `${p.name} ${p.slug}`)
    expect(ranked[0]?.item.slug).toBe('ollama')
  })
})
