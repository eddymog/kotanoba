import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { importText } from "../api/texts";

export function ImportPage() {
  const [title, setTitle] = useState("");
  const [text, setText] = useState("");
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  // decision #4 in design.md: synchronous for Slice 1. This request blocks
  // on a real Sudachi call — a long paste can take a moment; the disabled
  // submit button + "Tokenizing..." label is the entire loading story for
  // now, matching the thin-slice scope.
  const mutation = useMutation({
    mutationFn: () => importText(text, title.trim() || undefined),
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ["texts"] });
      navigate(`/texts/${created.id}`);
    },
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (text.trim()) {
      mutation.mutate();
    }
  }

  return (
    <main className="import-page">
      <h1>Import a text</h1>
      <form onSubmit={handleSubmit}>
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Title (optional — taken from the text if left blank)"
        />
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Paste Japanese text here..."
          rows={12}
          autoFocus
        />
        {mutation.isError && <p className="error">Import failed. Is the NLP service running?</p>}
        <button type="submit" disabled={mutation.isPending || !text.trim()}>
          {mutation.isPending ? "Tokenizing..." : "Import"}
        </button>
      </form>
    </main>
  );
}
