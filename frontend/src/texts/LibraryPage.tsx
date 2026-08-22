import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { listTexts } from "../api/texts";

export function LibraryPage() {
  const { data: texts, isLoading, isError } = useQuery({ queryKey: ["texts"], queryFn: listTexts });

  return (
    <main className="library-page">
      <div className="library-header">
        <h1>Library</h1>
        <Link to="/import" className="button-link">
          + Import text
        </Link>
      </div>
      {isLoading && <p>Loading...</p>}
      {isError && <p className="error">Could not load your library.</p>}
      {texts && texts.length === 0 && <p>No texts yet — import one to get started.</p>}
      <ul className="library-list">
        {texts?.map((text) => (
          <li key={text.id}>
            <Link to={`/texts/${text.id}`}>
              <span className="title">{text.title}</span>
              <span className="meta">
                {text.tokenCount} tokens · {text.distinctLemmaCount} distinct words
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </main>
  );
}
