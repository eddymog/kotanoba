// CJK Unified Ideographs (U+4E00-U+9FFF) + Extension A (U+3400-U+4DBF) —
// enough to catch real kanji without pulling in a dependency. Used to decide
// whether a token needs furigana at all: a kana-only surface form (かんしゃく)
// reading itself back in kana above itself would just be noise.
const KANJI_PATTERN = /[一-鿿㐀-䶿]/;

export function hasKanji(text: string): boolean {
  return KANJI_PATTERN.test(text);
}
