import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { deleteText, listTexts } from "../api/texts";
import type { TextSortOrder } from "../api/types";
import { relativeTime } from "./relativeTime";

const SORT_OPTIONS: { value: TextSortOrder; label: string }[] = [
  { value: "DIFFICULTY", label: "Most readable" },
  { value: "RECENT", label: "Recently added" },
];

function difficultyBadge(score: number | null): { label: string; className: string } {
  if (score === null) return { label: "—", className: "" };
  if (score >= 0.85) return { label: `${Math.round(score * 100)}% known`, className: "difficulty-badge--easy" };
  if (score >= 0.5) return { label: `${Math.round(score * 100)}% known`, className: "difficulty-badge--medium" };
  return { label: `${Math.round(score * 100)}% known`, className: "difficulty-badge--hard" };
}

export function LibraryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Math.max(1, Number(searchParams.get("page") ?? "1"));
  const sort = (searchParams.get("sort") as TextSortOrder | null) ?? "DIFFICULTY";
  const q = searchParams.get("q") ?? "";
  const [searchInput, setSearchInput] = useState(q);
  const queryClient = useQueryClient();

  const queryKey = ["texts", page, q, sort];
  const { data, isLoading, isError } = useQuery({ queryKey, queryFn: () => listTexts(page, q, sort) });

  const deleteMutation = useMutation({
    mutationFn: deleteText,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["texts"] }),
  });

  function updateParams(next: Record<string, string | null>) {
    setSearchParams((prev) => {
      const params = new URLSearchParams(prev);
      for (const [key, value] of Object.entries(next)) {
        if (value === null || value === "") {
          params.delete(key);
        } else {
          params.set(key, value);
        }
      }
      return params;
    });
  }

  function handleSearchSubmit(e: React.FormEvent) {
    e.preventDefault();
    updateParams({ q: searchInput, page: "1" });
  }

  function handleDelete(id: number, title: string) {
    if (window.confirm(`Delete "${title}"? This can't be undone.`)) {
      deleteMutation.mutate(id);
    }
  }

  return (
    <main className="library-page">
      <div className="library-header">
        <h1>Library</h1>
        <Link to="/import" className="button-link">
          + Import text
        </Link>
      </div>

      <div className="library-controls">
        <form className="library-search" onSubmit={handleSearchSubmit}>
          <input
            type="search"
            placeholder="Search titles..."
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />
          <button type="submit">Search</button>
        </form>
        <div className="library-sort">
          {SORT_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              className={`vocabulary-filter${sort === opt.value ? " vocabulary-filter--active" : ""}`}
              onClick={() => updateParams({ sort: opt.value, page: "1" })}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {isLoading && <p>Loading...</p>}
      {isError && <p className="error">Could not load your library.</p>}
      {data && data.texts.length === 0 && <p>{q ? "No texts match that search." : "No texts yet — import one to get started."}</p>}

      <ul className="library-list">
        {data?.texts.map((text) => {
          const badge = difficultyBadge(text.difficultyScore);
          return (
            <li key={text.id}>
              <Link to={`/texts/${text.id}`}>
                <span className="title">{text.title}</span>
                <span className="meta">
                  {text.tokenCount} tokens · {text.distinctLemmaCount} distinct words · imported{" "}
                  {relativeTime(text.createdAt)}
                  {text.lastOpenedAt && <> · last opened {relativeTime(text.lastOpenedAt)}</>}
                </span>
              </Link>
              <span className={`difficulty-badge ${badge.className}`}>{badge.label}</span>
              <button
                type="button"
                className="library-delete"
                aria-label={`Delete ${text.title}`}
                onClick={() => handleDelete(text.id, text.title)}
              >
                Delete
              </button>
            </li>
          );
        })}
      </ul>

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
