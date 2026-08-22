import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router-dom";
import { getText, setLemmaStatus } from "../api/texts";
import type { LemmaStatus, TextDetail } from "../api/types";
import { TokenSpan } from "./TokenSpan";

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

  if (isLoading) return <p>Loading...</p>;
  if (isError || !text) return <p className="error">Could not load this text.</p>;

  return (
    <main className="reader-page" onClick={() => setOpenPosition(null)}>
      <h1>{text.title}</h1>
      <div className="reader-tokens">
        {text.tokens.map((token) => (
          <TokenSpan
            key={token.position}
            token={token}
            isPickerOpen={token.position === openPosition}
            onClick={() => setOpenPosition(token.position === openPosition ? null : token.position)}
            onPickStatus={(status) => {
              if (token.lemmaId !== null) {
                statusMutation.mutate({ lemmaId: token.lemmaId, status });
              }
            }}
          />
        ))}
      </div>
    </main>
  );
}
