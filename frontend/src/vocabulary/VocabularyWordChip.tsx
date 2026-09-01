import type { LemmaStatus, VocabularyWord } from "../api/types";
import { WordDetailModal } from "../shared/WordDetailModal";
import { katakanaToRomaji } from "./katakanaToRomaji";

interface VocabularyWordChipProps {
  word: VocabularyWord;
  isPickerOpen: boolean;
  onClick: () => void;
  onPickStatus: (status: LemmaStatus) => void;
}

// Mirrors TokenSpan's picker interaction (reader page) rather than reusing it
// directly — TokenSpan is keyed by an existing lemmaId, this is keyed by
// term+reading since a frequency-list word may have no lemma row yet
// (VocabularyLemmaResolver creates one on first status change here).
export function VocabularyWordChip({ word, isPickerOpen, onClick, onPickStatus }: VocabularyWordChipProps) {
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
