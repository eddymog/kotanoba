"""Request/response models for the tokenizer service.

These are the contract. The Java client is generated from the OpenAPI schema
these produce, so a change here is a compile error on the Spring side — which is
the point (see claude.md, "contract drift is a compile error").
"""

from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field


class SplitMode(str, Enum):
    """Sudachi's segmentation granularity.

    Japanese has no spaces, so where a word ends is a judgement call. Sudachi
    exposes that as a knob rather than making you build it:

        A  東京 / 都 / 庁      finest
        B  東京 / 都庁         middle
        C  東京都庁            coarsest, keeps named entities whole

    C is the default: a learner looking up 東京都庁 wants the whole thing, not
    three fragments they already know.
    """

    A = "A"
    B = "B"
    C = "C"


class TokenizeRequest(BaseModel):
    text: str = Field(..., description="Raw Japanese text to analyse.")
    mode: SplitMode = Field(
        default=SplitMode.C,
        description="Segmentation granularity. See SplitMode.",
    )


class Token(BaseModel):
    """One token, carrying everything the import worker needs to persist it.

    Maps onto `text_token` (surface/reading/offsets/is_word) and `lemma`
    (normalized_form/dictionary_form/part_of_speech) in V1__initial_schema.sql.
    """

    surface: str = Field(..., description="Text exactly as it appeared.")

    char_start: int = Field(
        ...,
        description=(
            "Start offset into the original text, in UTF-16 code units so it "
            "indexes a Java String directly. Half-open with char_end."
        ),
    )
    char_end: int = Field(..., description="End offset, exclusive.")

    is_word: bool = Field(
        ...,
        description=(
            "False for punctuation, symbols and whitespace. Those still get a "
            "token so the reader can rebuild the document in order, but they "
            "carry no lemma."
        ),
    )

    normalized_form: Optional[str] = Field(
        None,
        description=(
            "Sudachi's normalized form — collapses script variants, so できる "
            "and 出来る land on the same value. This is what user status "
            "attaches to. Null when is_word is false."
        ),
    )
    dictionary_form: Optional[str] = Field(
        None, description="Uninflected form: 食べます -> 食べる. Null for non-words."
    )
    reading: Optional[str] = Field(
        None,
        description=(
            "Katakana reading as analysed *in this sentence*, which is why "
            "furigana has to come from here and not a kanji lookup table: 今日 "
            "is キョウ or コンニチ depending on context. Null for non-words."
        ),
    )
    part_of_speech: Optional[str] = Field(
        None, description="Comma-joined Sudachi POS tuple. Null for non-words."
    )


class TokenizeResponse(BaseModel):
    tokens: List[Token]
    token_count: int = Field(..., description="Convenience: len(tokens).")
    word_count: int = Field(..., description="Tokens with is_word true.")
    distinct_lemma_count: int = Field(
        ..., description="Distinct normalized forms — the text's vocabulary size."
    )
