import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { getText, saveReadPosition, setLemmaStatus, updateTextTitle } from "../api/texts";
import type { LemmaStatus, TextDetail } from "../api/types";
import { TokenSpan } from "./TokenSpan";

const STATUSES: LemmaStatus[] = ["NEW", "LEARNING", "KNOWN", "IGNORED"];

const LEGEND: { status: LemmaStatus; label: string }[] = [
  { status: "NEW", label: "New" },
  { status: "LEARNING", label: "Learning" },
  { status: "KNOWN", label: "Known" },
  { status: "IGNORED", label: "Ignored" },
];

const FURIGANA_PREFERENCE_KEY = "kotanoba:show-furigana";

// A per-viewer display preference, not app state — localStorage, not the
// backend. Defaults to on (the behavior before this was toggleable at all).
// Wrapped in try/catch: a private window or blocked storage just means the
// choice doesn't survive reload, not a broken toggle.
function loadFuriganaPreference(): boolean {
  try {
    return localStorage.getItem(FURIGANA_PREFERENCE_KEY) !== "false";
  } catch {
    return true;
  }
}

function saveFuriganaPreference(value: boolean) {
  try {
    localStorage.setItem(FURIGANA_PREFERENCE_KEY, String(value));
  } catch {
    // Best-effort, see loadFuriganaPreference above.
  }
}

function progressTally(text: TextDetail): Record<LemmaStatus, number> {
  const statusByLemmaId = new Map<number, LemmaStatus>();
  for (const token of text.tokens) {
    if (token.lemmaId !== null && token.status) {
      statusByLemmaId.set(token.lemmaId, token.status);
    }
  }
  const tally: Record<LemmaStatus, number> = { NEW: 0, LEARNING: 0, KNOWN: 0, IGNORED: 0 };
  for (const status of statusByLemmaId.values()) {
    tally[status]++;
  }
  return tally;
}

export function ReaderPage() {
  const { id } = useParams<{ id: string }>();
  const textId = Number(id);
  const queryClient = useQueryClient();
  // Keyed by position, not lemmaId: a lemma can appear at multiple positions
  // in the same text (は shows up twice in the design.md example). Keying by
  // lemmaId would pop a picker open under every occurrence at once when only
  // one was clicked. The status update this picker triggers still applies to
  // the whole lemma — only which picker is visually open is per-occurrence.
  const [openPosition, setOpenPosition] = useState<number | null>(null);
  const [isEditingTitle, setIsEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [showFurigana, setShowFurigana] = useState(loadFuriganaPreference);

  function toggleFurigana() {
    setShowFurigana((current) => {
      const next = !current;
      saveFuriganaPreference(next);
      return next;
    });
  }

  // design.md §15: saved once, on leaving the reader — not on every click. A
  // ref, not state, so updating it never triggers a re-render and the
  // unmount effect below always reads the latest value without going stale.
  const lastPositionRef = useRef<number | null>(null);
  const hasScrolledToSavedPositionRef = useRef(false);

  const {
    data: text,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ["text", textId],
    queryFn: () => getText(textId),
  });

  // Optimistic, and applied to every token sharing this lemma — not just the
  // one clicked. That's the actual point of the domain model (status
  // attaches to the lemma, not the occurrence; design.md's は example): the
  // UI should visibly prove it instantly, not after a refetch.
  const statusMutation = useMutation({
    mutationFn: ({ lemmaId, status }: { lemmaId: number; status: LemmaStatus }) => setLemmaStatus(lemmaId, status),
    onMutate: async ({ lemmaId, status }) => {
      await queryClient.cancelQueries({ queryKey: ["text", textId] });
      const previous = queryClient.getQueryData<TextDetail>(["text", textId]);
      if (previous) {
        queryClient.setQueryData<TextDetail>(["text", textId], {
          ...previous,
          tokens: previous.tokens.map((t) => (t.lemmaId === lemmaId ? { ...t, status } : t)),
        });
      }
      return { previous };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(["text", textId], context.previous);
      }
    },
    onSettled: () => setOpenPosition(null),
  });

  // design.md §20: renaming a text. Optimistic on the reader's own cache, and
  // the library list's cache is invalidated too (design.md §14's page shows
  // the same title) rather than patched — the library query isn't guaranteed
  // to be mounted/cached with this text on the current page, so there's
  // nothing safe to patch in place.
  const titleMutation = useMutation({
    mutationFn: (title: string) => updateTextTitle(textId, title),
    onMutate: async (title) => {
      await queryClient.cancelQueries({ queryKey: ["text", textId] });
      const previous = queryClient.getQueryData<TextDetail>(["text", textId]);
      if (previous) {
        queryClient.setQueryData<TextDetail>(["text", textId], { ...previous, title });
      }
      return { previous };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(["text", textId], context.previous);
      }
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["texts"] }),
    onSettled: () => setIsEditingTitle(false),
  });

  function startEditingTitle(currentTitle: string) {
    setTitleDraft(currentTitle);
    setIsEditingTitle(true);
  }

  function saveTitle(currentTitle: string) {
    const trimmed = titleDraft.trim();
    if (trimmed && trimmed !== currentTitle) {
      titleMutation.mutate(trimmed);
    } else {
      setIsEditingTitle(false);
    }
  }

  function pickStatus(lemmaId: number | null, status: LemmaStatus) {
    if (lemmaId !== null) {
      statusMutation.mutate({ lemmaId, status });
    }
  }

  function openToken(position: number) {
    setOpenPosition((current) => (position === current ? null : position));
    lastPositionRef.current = position;
  }

  // Number-key shortcuts (1-4, matching the status order shown in each
  // token's popover) — only listening while a picker is actually open, so
  // typing elsewhere on the page is never intercepted.
  useEffect(() => {
    if (openPosition === null || !text) return;
    const openText = text;
    function handleKeyDown(e: KeyboardEvent) {
      const index = Number(e.key) - 1;
      if (index < 0 || index >= STATUSES.length) return;
      const token = openText.tokens.find((t) => t.position === openPosition);
      if (token) {
        pickStatus(token.lemmaId, STATUSES[index]);
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [openPosition, text]);

  // Resume where you left off — once, the first time this text's tokens are
  // available, not on every refetch after a status change.
  useEffect(() => {
    if (!text || hasScrolledToSavedPositionRef.current || text.lastReadPosition === null) return;
    hasScrolledToSavedPositionRef.current = true;
    lastPositionRef.current = text.lastReadPosition;
    const el = document.querySelector(`[data-token-position="${text.lastReadPosition}"]`);
    el?.scrollIntoView({ block: "center" });
  }, [text]);

  // Save on leaving the reader, not on every click (design.md §15). Keyed on
  // textId, not on text/tokens — navigating from one text straight to
  // another (without the component ever fully unmounting) still saves the
  // outgoing text's position first, exactly like a real unmount would.
  useEffect(() => {
    return () => {
      if (lastPositionRef.current !== null) {
        saveReadPosition(textId, lastPositionRef.current).catch(() => {
          // Best-effort — losing the exact resume spot on a failed request
          // isn't worth surfacing an error for on the way out of the page.
        });
      }
    };
  }, [textId]);

  if (isLoading) return <p>Loading...</p>;
  if (isError || !text) return <p className="error">Could not load this text.</p>;

  const tally = progressTally(text);

  return (
    <main className="reader-page" onClick={() => setOpenPosition(null)}>
      {isEditingTitle ? (
        <input
          type="text"
          className="reader-title-input"
          value={titleDraft}
          autoFocus
          onClick={(e) => e.stopPropagation()}
          onChange={(e) => setTitleDraft(e.target.value)}
          onBlur={() => saveTitle(text.title)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              e.currentTarget.blur();
            } else if (e.key === "Escape") {
              setIsEditingTitle(false);
            }
          }}
        />
      ) : (
        <h1
          className="reader-title"
          title="Click to rename"
          onClick={(e) => {
            e.stopPropagation();
            startEditingTitle(text.title);
          }}
        >
          {text.title}
        </h1>
      )}
      <div className="reader-toolbar">
        <div className="reader-progress">
          {tally.KNOWN} known · {tally.LEARNING} learning · {tally.NEW} new
          {tally.IGNORED > 0 && ` · ${tally.IGNORED} ignored`}
        </div>
        <div className="status-legend">
          {LEGEND.map((item) => (
            <span key={item.status} className="status-legend__item">
              <span className={`status-legend__swatch status-legend__swatch--${item.status.toLowerCase()}`} />
              {item.label}
            </span>
          ))}
        </div>
        <button
          type="button"
          className={`vocabulary-filter${showFurigana ? " vocabulary-filter--active" : ""}`}
          onClick={(e) => {
            e.stopPropagation();
            toggleFurigana();
          }}
        >
          Furigana: {showFurigana ? "On" : "Off"}
        </button>
      </div>
      <div className={`reader-tokens${showFurigana ? "" : " reader-tokens--no-furigana"}`}>
        {text.tokens.map((token) => (
          <TokenSpan
            key={token.position}
            token={token}
            isPickerOpen={token.position === openPosition}
            showFurigana={showFurigana}
            onClick={() => openToken(token.position)}
            onPickStatus={(status) => pickStatus(token.lemmaId, status)}
          />
        ))}
      </div>
    </main>
  );
}
