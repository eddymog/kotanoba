import type { LemmaStatus, TokenView } from "../api/types";

const STATUSES: LemmaStatus[] = ["NEW", "LEARNING", "KNOWN", "IGNORED"];

interface TokenSpanProps {
  token: TokenView;
  isPickerOpen: boolean;
  onClick: () => void;
  onPickStatus: (status: LemmaStatus) => void;
}

export function TokenSpan({ token, isPickerOpen, onClick, onPickStatus }: TokenSpanProps) {
  if (!token.isWord) {
    // Punctuation/whitespace: render as plain text, not clickable — it never
    // had a lemma to set a status on (design.md §2, §5).
    return <span>{token.surfaceText}</span>;
  }

  return (
    <span className="token-wrapper">
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
        title={token.reading ?? undefined}
      >
        {token.surfaceText}
      </span>
      {isPickerOpen && (
        <span className="status-picker">
          {STATUSES.map((status) => (
            <button
              key={status}
              type="button"
              className={`status-picker__option status-picker__option--${status.toLowerCase()}`}
              onClick={(e) => {
                e.stopPropagation();
                onPickStatus(status);
              }}
            >
              {status}
            </button>
          ))}
        </span>
      )}
    </span>
  );
}
