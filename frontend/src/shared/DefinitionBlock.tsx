// Senses (design.md §18's dictionary_entry, replacing the old single
// collapsed meaning string) plus an optional example sentence
// (word_example) — shared markup for the reader and both vocabulary-page
// chips, since all three show the exact same thing in the same picker
// layout.
interface DefinitionBlockProps {
  senses: string[] | null;
  exampleJapanese: string | null;
  exampleEnglish: string | null;
}

export function DefinitionBlock({ senses, exampleJapanese, exampleEnglish }: DefinitionBlockProps) {
  return (
    <>
      {senses && senses.length > 0 ? (
        <ul className="status-picker__senses">
          {senses.map((sense, index) => (
            <li key={index}>{sense}</li>
          ))}
        </ul>
      ) : (
        <span className="status-picker__meaning status-picker__meaning--missing">No definition found</span>
      )}
      {exampleJapanese && exampleEnglish && (
        <div className="status-picker__example">
          <div className="status-picker__example-ja">{exampleJapanese}</div>
          <div className="status-picker__example-en">{exampleEnglish}</div>
        </div>
      )}
    </>
  );
}
