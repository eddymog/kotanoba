import { useEffect } from "react";
import type { LemmaStatus } from "../api/types";
import { DefinitionBlock } from "./DefinitionBlock";
import { partOfSpeechLabel } from "./partOfSpeechLabel";

const STATUSES: LemmaStatus[] = ["NEW", "LEARNING", "KNOWN", "IGNORED"];

interface WordDetailModalProps {
  term: string;
  reading: string | null;
  partOfSpeech: string | null;
  senses: string[] | null;
  exampleJapanese: string | null;
  exampleEnglish: string | null;
  onPickStatus: (status: LemmaStatus) => void;
  onClose: () => void;
  // Only the reader listens for 1-4 number keys while a word is open
  // (ReaderPage's own effect) — the hints are shown here, not wired here,
  // so the vocabulary pages' chips don't advertise a shortcut that does
  // nothing on those pages.
  showKeyboardHints?: boolean;
}

// Shared by the reader (TokenSpan), the ranked vocabulary list
// (VocabularyWordChip), and the "other words" list (OtherVocabularyWordChip)
// — replaces the old small inline popover, which had no real room for
// senses/example sentence (design.md §18) once those existed.
export function WordDetailModal({
  term,
  reading,
  partOfSpeech,
  senses,
  exampleJapanese,
  exampleEnglish,
  onPickStatus,
  onClose,
  showKeyboardHints = false,
}: WordDetailModalProps) {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div className="word-modal-backdrop" onClick={onClose}>
      <div className="word-modal" onClick={(e) => e.stopPropagation()}>
        <button type="button" className="word-modal__close" onClick={onClose} aria-label="Close">
          ×
        </button>
        <div className="word-modal__header">
          <span className="word-modal__term">{term}</span>
          {reading && <span className="word-modal__reading">{reading}</span>}
        </div>
        {partOfSpeech && <span className="status-picker__pos">{partOfSpeechLabel(partOfSpeech)}</span>}
        <DefinitionBlock senses={senses} exampleJapanese={exampleJapanese} exampleEnglish={exampleEnglish} />
        <div className="word-modal__statuses">
          {STATUSES.map((status, index) => (
            <button
              key={status}
              type="button"
              className={`status-picker__option status-picker__option--${status.toLowerCase()}`}
              onClick={() => onPickStatus(status)}
            >
              {showKeyboardHints && <kbd>{index + 1}</kbd>} {status}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
