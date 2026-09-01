import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { setLemmaStatus } from "../api/texts";
import { listOtherVocabulary, listVocabulary, setVocabularyStatus } from "../api/vocabulary";
import type {
  LemmaStatus,
  OtherVocabularyPage as OtherVocabularyPageData,
  VocabularyPage as VocabularyPageData,
} from "../api/types";
import { POS_CATEGORY_OPTIONS } from "../shared/partOfSpeechLabel";
import { OtherVocabularyWordChip } from "./OtherVocabularyWordChip";
import { VocabularyWordChip } from "./VocabularyWordChip";

const STATUS_FILTERS: (LemmaStatus | "ALL")[] = ["ALL", "NEW", "LEARNING", "KNOWN", "IGNORED"];

type Tab = "top" | "other";

function parseStatusFilter(raw: string | null): LemmaStatus | undefined {
  return raw === "NEW" || raw === "LEARNING" || raw === "KNOWN" || raw === "IGNORED" ? raw : undefined;
}

function parseTab(raw: string | null): Tab {
  return raw === "other" ? "other" : "top";
}

export function VocabularyPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = parseTab(searchParams.get("tab"));
  const page = Math.max(1, Number(searchParams.get("page") ?? "1"));
  const statusFilter = parseStatusFilter(searchParams.get("status"));
  const posFilter = searchParams.get("pos") ?? undefined;
  const queryClient = useQueryClient();
  // Keyed by rank (top-10k tab) or lemmaId (other-words tab) — whichever the
  // active tab uses as its stable per-word identity. Mirrors ReaderPage's
  // openPosition for the same reason: opening one word's picker shouldn't
  // open every occurrence, and switching tabs always closes it.
  const [openKey, setOpenKey] = useState<number | null>(null);

  const topQueryKey = ["vocabulary", page, statusFilter ?? "ALL", posFilter ?? "ALL"];
  const topQuery = useQuery({
    queryKey: topQueryKey,
    queryFn: () => listVocabulary(page, statusFilter, posFilter),
    enabled: tab === "top",
  });

  const otherQueryKey = ["vocabulary-other", page, statusFilter ?? "ALL", posFilter ?? "ALL"];
  const otherQuery = useQuery({
    queryKey: otherQueryKey,
    queryFn: () => listOtherVocabulary(page, statusFilter, posFilter),
    enabled: tab === "other",
  });

  const topStatusMutation = useMutation({
    mutationFn: ({ term, reading, status }: { term: string; reading: string; status: LemmaStatus }) =>
      setVocabularyStatus(term, reading, status),
    onMutate: async ({ term, reading, status }) => {
      await queryClient.cancelQueries({ queryKey: topQueryKey });
      const previous = queryClient.getQueryData<VocabularyPageData>(topQueryKey);
      if (previous) {
        queryClient.setQueryData<VocabularyPageData>(topQueryKey, {
          ...previous,
          words: previous.words.map((w) => (w.term === term && w.reading === reading ? { ...w, status } : w)),
        });
      }
      return { previous };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(topQueryKey, context.previous);
      }
    },
    onSettled: () => {
      setOpenKey(null);
      // Under a filter, a status change can remove a word from view (marking
      // a NEW-filtered word KNOWN, say) or pull one in from the next page —
      // the optimistic patch above updates the word in place but can't know
      // that. Reconcile with a refetch rather than getting it exactly right
      // optimistically.
      queryClient.invalidateQueries({ queryKey: topQueryKey });
    },
  });

  const otherStatusMutation = useMutation({
    mutationFn: ({ lemmaId, status }: { lemmaId: number; status: LemmaStatus }) => setLemmaStatus(lemmaId, status),
    onMutate: async ({ lemmaId, status }) => {
      await queryClient.cancelQueries({ queryKey: otherQueryKey });
      const previous = queryClient.getQueryData<OtherVocabularyPageData>(otherQueryKey);
      if (previous) {
        queryClient.setQueryData<OtherVocabularyPageData>(otherQueryKey, {
          ...previous,
          words: previous.words.map((w) => (w.lemmaId === lemmaId ? { ...w, status } : w)),
        });
      }
      return { previous };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(otherQueryKey, context.previous);
      }
    },
    onSettled: () => {
      setOpenKey(null);
      queryClient.invalidateQueries({ queryKey: otherQueryKey });
    },
  });

  function updateParams(next: Record<string, string | null>) {
    setSearchParams((prev) => {
      const params = new URLSearchParams(prev);
      for (const [key, value] of Object.entries(next)) {
        if (value === null) {
          params.delete(key);
        } else {
          params.set(key, value);
        }
      }
      return params;
    });
    setOpenKey(null);
  }

  function selectTab(next: Tab) {
    updateParams({ tab: next === "top" ? null : next, page: "1" });
  }

  function selectStatusFilter(filter: LemmaStatus | "ALL") {
    updateParams({ status: filter === "ALL" ? null : filter, page: "1" });
  }

  function selectPosFilter(value: string) {
    updateParams({ pos: value === "" ? null : value, page: "1" });
  }

  const data = tab === "top" ? topQuery.data : otherQuery.data;
  const isLoading = tab === "top" ? topQuery.isLoading : otherQuery.isLoading;
  const isError = tab === "top" ? topQuery.isError : otherQuery.isError;

  return (
    <main className="vocabulary-page" onClick={() => setOpenKey(null)}>
      <div className="vocabulary-header">
        <h1>Vocabulary</h1>
        <p className="meta">
          {tab === "top"
            ? "Most common words, ranked by frequency. Click a word to set its status."
            : "Words you've read that aren't in the top 10,000 most common words — no frequency rank or dictionary meaning for these, just what you've actually encountered."}
        </p>
      </div>

      <div className="vocabulary-tabs">
        <button
          type="button"
          className={`vocabulary-filter${tab === "top" ? " vocabulary-filter--active" : ""}`}
          onClick={(e) => {
            e.stopPropagation();
            selectTab("top");
          }}
        >
          Top 10,000
        </button>
        <button
          type="button"
          className={`vocabulary-filter${tab === "other" ? " vocabulary-filter--active" : ""}`}
          onClick={(e) => {
            e.stopPropagation();
            selectTab("other");
          }}
        >
          Other words
        </button>
      </div>

      <div className="vocabulary-filters">
        {STATUS_FILTERS.map((filter) => (
          <button
            key={filter}
            type="button"
            className={`vocabulary-filter${(statusFilter ?? "ALL") === filter ? " vocabulary-filter--active" : ""}`}
            onClick={(e) => {
              e.stopPropagation();
              selectStatusFilter(filter);
            }}
          >
            {filter === "ALL" ? "All" : filter.charAt(0) + filter.slice(1).toLowerCase()}
          </button>
        ))}
        <select
          className="vocabulary-pos-filter"
          value={posFilter ?? ""}
          onClick={(e) => e.stopPropagation()}
          onChange={(e) => selectPosFilter(e.target.value)}
        >
          <option value="">All word types</option>
          {POS_CATEGORY_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </div>

      {isLoading && <p>Loading...</p>}
      {isError && <p className="error">Could not load the vocabulary list.</p>}
      {data && data.words.length === 0 && (
        <p>{tab === "top" ? "No words match this filter." : "No other words yet — read a bit more and check back."}</p>
      )}

      {data && (
        <div className="vocabulary-grid">
          {tab === "top" &&
            topQuery.data?.words.map((word) => (
              <span key={word.rank} className="vocabulary-entry">
                <span className="vocabulary-rank">{word.rank}</span>
                <VocabularyWordChip
                  word={word}
                  isPickerOpen={word.rank === openKey}
                  onClick={() => setOpenKey(word.rank === openKey ? null : word.rank)}
                  onPickStatus={(status) =>
                    topStatusMutation.mutate({ term: word.term, reading: word.reading, status })
                  }
                />
              </span>
            ))}
          {tab === "other" &&
            otherQuery.data?.words.map((word) => (
              <span key={word.lemmaId} className="vocabulary-entry">
                <OtherVocabularyWordChip
                  word={word}
                  isPickerOpen={word.lemmaId === openKey}
                  onClick={() => setOpenKey(word.lemmaId === openKey ? null : word.lemmaId)}
                  onPickStatus={(status) => otherStatusMutation.mutate({ lemmaId: word.lemmaId, status })}
                />
              </span>
            ))}
        </div>
      )}

      {data && data.totalPages > 1 && (
        <div className="vocabulary-pager">
          <button type="button" disabled={page <= 1} onClick={() => updateParams({ page: String(page - 1) })}>
            ← Previous
          </button>
          <span>
            Page {data.page} of {data.totalPages}
          </span>
          <button
            type="button"
            disabled={page >= data.totalPages}
            onClick={() => updateParams({ page: String(page + 1) })}
          >
            Next →
          </button>
        </div>
      )}
    </main>
  );
}
