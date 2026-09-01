import type { LemmaStatus, OtherVocabularyWord } from "../api/types";
import { WordDetailModal } from "../shared/WordDetailModal";
import { katakanaToRomaji } from "./katakanaToRomaji";

interface OtherVocabularyWordChipProps {
  word: OtherVocabularyWord;
  isPickerOpen: boolean;
  onClick: () => void;
  onPickStatus: (status: LemmaStatus) => void;
}

// Mirrors VocabularyWordChip, minus rank (doesn't apply outside the top
// 10k). senses/example still come through (design.md §18's dictionary_entry/
// word_example cover far more than the top 10k) — see design.md §17.
export function OtherVocabularyWordChip({ word, isPickerOpen, onClick, onPickStatus }: OtherVocabularyWordChipProps) {
  return (
    <span className="token-wrapper">
      <span
        className="vocabulary-word-stack"
        onClick={(e) => {
          e.stopPropagation();
          onClick();
        }}
      >
        <span className="vocabulary-romaji">{katakanaToRomaji(word.reading)}</span>
        <span className="vocabulary-kana">{word.reading}</span>
        <span className={`token token--${word.status.toLowerCase()} vocabulary-word`}>{word.term}</span>
      </span>
      {isPickerOpen && (
        <WordDetailModal
          term={word.term}
          reading={word.reading}
          partOfSpeech={word.partOfSpeech}
          senses={word.senses}
          exampleJapanese={word.exampleJapanese}
          exampleEnglish={word.exampleEnglish}
          onPickStatus={onPickStatus}
          onClose={onClick}
        />
      )}
    </span>
  );
}
