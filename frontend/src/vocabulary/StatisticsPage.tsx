import { useQuery } from "@tanstack/react-query";
import { getVocabularyStats } from "../api/vocabulary";
import type { StatusCounts } from "../api/types";

const SEGMENTS: { key: keyof StatusCounts; label: string; modifier: string }[] = [
  { key: "knownCount", label: "Known", modifier: "known" },
  { key: "learningCount", label: "Learning", modifier: "learning" },
  { key: "newCount", label: "New", modifier: "new" },
  { key: "ignoredCount", label: "Ignored", modifier: "ignored" },
];

function CategoryStats({ title, description, counts }: { title: string; description: string; counts: StatusCounts }) {
  return (
    <section className="stats-category">
      <h2>{title}</h2>
      <p className="meta">{description}</p>
      {counts.total === 0 ? (
        <p className="stats-empty">Nothing here yet.</p>
      ) : (
        <>
          <div className="stats-bar">
            {SEGMENTS.map(({ key, modifier }) => {
              const value = counts[key] as number;
              if (value === 0) return null;
              return (
                <div
                  key={key}
                  className={`stats-bar__segment stats-bar__segment--${modifier}`}
                  style={{ width: `${(value / counts.total) * 100}%` }}
                  title={`${value} ${modifier}`}
                />
              );
            })}
          </div>
          <ul className="stats-breakdown">
            {SEGMENTS.map(({ key, label, modifier }) => (
              <li key={key}>
                <span className={`status-legend__swatch status-legend__swatch--${modifier}`} />
                {label}: {counts[key] as number}
              </li>
            ))}
            <li className="stats-breakdown__total">Total: {counts.total}</li>
          </ul>
        </>
      )}
    </section>
  );
}

export function StatisticsPage() {
  const { data, isLoading, isError } = useQuery({ queryKey: ["vocabulary-stats"], queryFn: getVocabularyStats });

  if (isLoading) return <p>Loading...</p>;
  if (isError || !data) return <p className="error">Could not load statistics.</p>;

  const totalKnown = data.topWords.knownCount + data.otherWords.knownCount;
  const totalLearning = data.topWords.learningCount + data.otherWords.learningCount;

  return (
    <main className="statistics-page">
      <div className="vocabulary-header">
        <h1>Statistics</h1>
        <p className="meta">How your vocabulary is split between the top 10,000 most common words and everything else you've read.</p>
      </div>

      <div className="stats-summary">
        <div className="stats-summary__card">
          <span className="stats-summary__value">{totalKnown}</span>
          <span className="stats-summary__label">Known words</span>
        </div>
        <div className="stats-summary__card">
          <span className="stats-summary__value">{totalLearning}</span>
          <span className="stats-summary__label">Learning</span>
        </div>
        <div className="stats-summary__card">
          <span className="stats-summary__value">{data.otherWords.total}</span>
          <span className="stats-summary__label">Other words encountered</span>
        </div>
      </div>

      <CategoryStats
        title="Top 10,000 words"
        description="Status of the most common words, ranked by frequency."
        counts={data.topWords}
      />
      <CategoryStats
        title="Other words"
        description="Words you've actually encountered while reading that fall outside the top 10,000."
        counts={data.otherWords}
      />
    </main>
  );
}
