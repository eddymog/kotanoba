"""Tokenizer behaviour tests.

These pin the specific Japanese-language guarantees the rest of the system is
built on. If Sudachi or its dictionary is swapped, these are what tell you
whether the assumptions in claude.md still hold.
"""

import pytest

from app.models import SplitMode
from app.tokenizer import tokenize


def words(text, mode=SplitMode.C):
    return [t for t in tokenize(text, mode).tokens if t.is_word]


def surfaces(text, mode=SplitMode.C):
    return [t.surface for t in tokenize(text, mode).tokens]


class TestScriptVariantNormalization:
    """claude.md problem 4: the same word written in kanji or kana must land on
    one lemma, or you can mark one spelling known and still see the other as new."""

    @pytest.mark.parametrize(
        "kana,kanji",
        [("できる", "出来る"), ("がんばる", "頑張る"), ("わたし", "私")],
    )
    def test_variants_share_a_normalized_form(self, kana, kanji):
        assert words(kana)[0].normalized_form == words(kanji)[0].normalized_form


class TestConjugation:
    """claude.md problem 3: inflected forms must resolve back to the dictionary
    form, which needs morphology rather than suffix stripping."""

    @pytest.mark.parametrize(
        "inflected", ["食べます", "食べた", "食べて", "食べられる", "食べ始める"]
    )
    def test_inflections_resolve_to_taberu(self, inflected):
        assert words(inflected)[0].dictionary_form == "食べる"


class TestContextualReading:
    """claude.md problem 2: readings depend on the sentence, so furigana must
    come from per-token analysis and not a kanji lookup table.

    行った is the clean demonstration: same three characters, two readings,
    disambiguated only by the surrounding words."""

    def test_same_surface_reads_differently_by_context(self):
        def reading_of(sentence, surface):
            return next(t.reading for t in words(sentence) if t.surface == surface)

        assert reading_of("学校に行った。", "行っ") == "イッ"
        assert reading_of("会議を行った。", "行っ") == "オコナッ"


class TestSegmentation:
    """claude.md problem 1: Japanese has no spaces, so segmentation is itself a
    decision. Mode C keeps compounds whole."""

    def test_mode_c_keeps_compound_whole(self):
        assert surfaces("東京都庁", SplitMode.C) == ["東京都庁"]

    def test_finer_modes_split_the_compound(self):
        assert len(surfaces("東京都庁", SplitMode.A)) > 1


class TestOffsets:
    """Offsets are UTF-16 code units so they index a Java String directly."""

    def test_offsets_are_contiguous_and_cover_the_input(self):
        text = "今日は東京都庁に行きました。"
        tokens = tokenize(text).tokens
        assert tokens[0].char_start == 0
        for a, b in zip(tokens, tokens[1:]):
            assert a.char_end == b.char_start, "tokens must tile the input with no gaps"
        assert tokens[-1].char_end == len(text.encode("utf-16-le")) // 2

    def test_offsets_survive_astral_characters(self):
        """An emoji is one code point in Python but two chars in Java. Slicing
        with our offsets must reproduce each token exactly on the Java side."""
        text = "猫🐱が好きです。"
        utf16 = text.encode("utf-16-le")
        for t in tokenize(text).tokens:
            java_substring = utf16[t.char_start * 2 : t.char_end * 2].decode("utf-16-le")
            assert java_substring == t.surface


class TestNonWords:
    """Punctuation still gets a token so the reader can rebuild the document,
    but carries no lemma — matching text_token's nullable lemma_id."""

    def test_punctuation_is_flagged_and_carries_no_lemma(self):
        token = next(t for t in tokenize("猫。").tokens if t.surface == "。")
        assert token.is_word is False
        assert token.normalized_form is None
        assert token.dictionary_form is None
        assert token.reading is None


class TestCounts:
    def test_distinct_lemma_count_deduplicates(self):
        # 猫 three times over -> one distinct lemma, three word tokens.
        result = tokenize("猫と猫と猫")
        assert result.distinct_lemma_count == 2  # 猫, と
        assert result.word_count == 5

    def test_empty_text_is_handled(self):
        result = tokenize("")
        assert result.tokens == []
        assert result.token_count == 0
        assert result.distinct_lemma_count == 0
