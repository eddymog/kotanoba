-- Fixes a real bug found while building the vocabulary browse page: jpdb's
-- source data stores readings in hiragana (V5's seed), but lemma.reading_form
-- is always Sudachi's katakana convention. Every join matching
-- `word_frequency.reading = lemma.reading_form` (TextLibraryRepository's
-- difficulty score, VocabularyBrowseRepository) silently never matched on
-- reading, so every lemma fell through to the unmatched-word floor weight
-- regardless of its real rank — difficulty scoring degraded to a flat
-- known/total ratio without erroring, which is why it wasn't caught by the
-- existing integration test (an all-known vs. all-unknown text scores 1.0
-- vs. 0.0 either way, whether real ranks are used or not).
--
-- V5 already ran against real data, so per this project's own precedent
-- (never rewrite a migration already applied — see V2/V3), this is a new
-- migration, not an edit to V5. The hiragana block (U+3041-3096) maps to the
-- katakana block (U+30A1-30F6) with a constant +0x60 codepoint offset for
-- every character in both, verified against real Sudachi output (の -> ノ,
-- たべる -> タベル) before writing this.
UPDATE word_frequency
SET reading = translate(
    reading,
    'ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕゖ',
    'ァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴヵヶ'
);
