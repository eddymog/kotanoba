"""Sudachi wrapper. Pure function: text in, tokens out.

No database, no user concept, no state beyond the loaded dictionary — per the
hard architectural rule in claude.md. If this service dies, imports retry; the
read path never touches it.
"""

import logging
import threading
from typing import List, Optional, Tuple

from sudachipy import dictionary, tokenizer as sudachi_tokenizer

from .models import SplitMode, Token, TokenizeResponse

logger = logging.getLogger(__name__)

# Sudachi POS tuples start with one of these for things that are not vocabulary.
# 補助記号 covers 。、「」!? etc; 空白 is whitespace; 記号 is symbols like ○ or ★.
_NON_WORD_POS = frozenset({"補助記号", "空白", "記号"})

_MODE_MAP = {
    SplitMode.A: sudachi_tokenizer.Tokenizer.SplitMode.A,
    SplitMode.B: sudachi_tokenizer.Tokenizer.SplitMode.B,
    SplitMode.C: sudachi_tokenizer.Tokenizer.SplitMode.C,
}

# The dictionary is ~100MB in memory and takes a couple of seconds to load, so
# it is built once per process and shared. Sudachi's Tokenizer objects are not
# documented as thread-safe, so each thread gets its own — cheap, since they all
# share the one underlying dictionary.
_dictionary = None
_dict_lock = threading.Lock()
_thread_local = threading.local()


def load_dictionary() -> None:
    """Load the Sudachi dictionary once, at startup, so the first import request
    does not pay the several-second load cost."""
    global _dictionary
    with _dict_lock:
        if _dictionary is None:
            logger.info("loading sudachi dictionary")
            _dictionary = dictionary.Dictionary()
            logger.info("sudachi dictionary loaded")


def _get_tokenizer():
    if _dictionary is None:
        load_dictionary()
    tok = getattr(_thread_local, "tokenizer", None)
    if tok is None:
        tok = _dictionary.create()
        _thread_local.tokenizer = tok
    return tok


def _utf16_offsets(text: str) -> List[int]:
    """Map code-point index -> UTF-16 code-unit offset.

    Python string indices count code points; Java String indices count UTF-16
    code units. They agree for almost all Japanese, but not for characters
    above the BMP — some rare kanji (U+20B9F) and any emoji, which show up
    constantly in blog and forum text. Those are one code point here and two
    char units in Java.

    Without this conversion every offset after the first such character is
    silently wrong on the Java side, and the reader highlights the wrong word.
    Returns len(text) + 1 entries so the last token's end offset resolves.
    """
    offsets = [0] * (len(text) + 1)
    acc = 0
    for i, ch in enumerate(text):
        offsets[i] = acc
        acc += 2 if ord(ch) > 0xFFFF else 1
    offsets[len(text)] = acc
    return offsets


def _first_pos(pos_tuple: Tuple[str, ...]) -> Optional[str]:
    return pos_tuple[0] if pos_tuple else None


def tokenize(text: str, mode: SplitMode = SplitMode.C) -> TokenizeResponse:
    """Analyse `text` and return tokens ready to persist.

    Every token in the input is returned, including punctuation and whitespace
    (flagged is_word=False), so the import worker can rebuild the document in
    order without re-parsing the original body.
    """
    if not text:
        return TokenizeResponse(
            tokens=[], token_count=0, word_count=0, distinct_lemma_count=0
        )

    tok = _get_tokenizer()
    offsets = _utf16_offsets(text)

    tokens: List[Token] = []
    distinct_lemmas = set()

    for m in tok.tokenize(text, _MODE_MAP[mode]):
        pos_tuple = m.part_of_speech()
        is_word = _first_pos(pos_tuple) not in _NON_WORD_POS

        normalized = m.normalized_form() if is_word else None
        if normalized:
            distinct_lemmas.add(normalized)

        tokens.append(
            Token(
                surface=m.surface(),
                char_start=offsets[m.begin()],
                char_end=offsets[m.end()],
                is_word=is_word,
                normalized_form=normalized,
                dictionary_form=m.dictionary_form() if is_word else None,
                reading=m.reading_form() if is_word else None,
                part_of_speech=",".join(pos_tuple) if is_word else None,
            )
        )

    return TokenizeResponse(
        tokens=tokens,
        token_count=len(tokens),
        word_count=sum(1 for t in tokens if t.is_word),
        distinct_lemma_count=len(distinct_lemmas),
    )
