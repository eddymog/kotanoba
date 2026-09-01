import type { LemmaStatus, TokenView } from "../api/types";
import { hasKanji } from "./hasKanji";
import { WordDetailModal } from "../shared/WordDetailModal";

interface TokenSpanProps {
  token: TokenView;
  isPickerOpen: boolean;
  showFurigana: boolean;
  onClick: () => void;
  onPickStatus: (status: LemmaStatus) => void;
}

export function TokenSpan({ token, isPickerOpen, showFurigana, onClick, onPickStatus }: TokenSpanProps) {
  if (!token.isWord) {
    // Punctuation/whitespace: render as plain text, not clickable — it never
    // had a lemma to set a status on (design.md §2, §5).
    return <span>{token.surfaceText}</span>;
  }

  // Furigana only for tokens that actually have kanji — a kana-only surface
  // form reading itself back above itself would just be noise. The reading
  // itself is already per-sentence-context-correct (claude.md's 今日 example),
  // not a static kanji-to-reading lookup, since it comes straight off this
  // token from the tokenizer. showFurigana (the prop) is the reader-wide
  // on/off toggle; renderRuby is "should this specific token get it."
  const renderRuby = showFurigana && token.reading && hasKanji(token.surfaceText);

  return (
    <span className="token-wrapper" data-token-position={token.position}>
      <span
        className={`token token--${(token.status ?? "NEW").toLowerCase()}`}
        onClick={(e) => {
          // Without this, the click bubbles to ReaderPage's <main
          // onClick={() => setOpenLemmaId(null)}> (there to close the picker
          // on an outside click) and immediately closes what this same
          // click just opened, in the same event cycle — the picker would
          // never actually appear.
          e.stopPropagation();
          onClick();
        }}
      >
        {renderRuby ? (
          <ruby>
            {token.surfaceText}
            <rt>{token.reading}</rt>
          </ruby>
        ) : (
          token.surfaceText
        )}
      </span>
      {isPickerOpen && (
        <WordDetailModal
          term={token.surfaceText}
          reading={token.reading}
          partOfSpeech={token.partOfSpeech}
          senses={token.senses}
          exampleJapanese={token.exampleJapanese}
          exampleEnglish={token.exampleEnglish}
          onPickStatus={onPickStatus}
          onClose={onClick}
          showKeyboardHints
        />
      )}
    </span>
  );
}
