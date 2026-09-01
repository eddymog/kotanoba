// Katakana -> Hepburn-ish romaji, for the small reading label on the
// vocabulary page. word_frequency/lemma readings are always katakana (see
// design.md's V6 migration note), never hiragana.
//
// Covers standard gojuon, dakuten/handakuten, youon (kya/sha/cho etc.), the
// common loanword digraphs (fa/fi/fe/fo, va/vi/vu/ve/vo, ti/di, che/je/she),
// sokuon (small ッ doubles the next consonant), and the long-vowel mark (ー
// repeats the previous vowel). Not exhaustive for rare loanword kana — good
// enough for real vocabulary, not a general-purpose IME.

const DIGRAPHS: Record<string, string> = {
  キャ: "kya", キュ: "kyu", キョ: "kyo",
  シャ: "sha", シュ: "shu", ショ: "sho",
  チャ: "cha", チュ: "chu", チョ: "cho",
  ニャ: "nya", ニュ: "nyu", ニョ: "nyo",
  ヒャ: "hya", ヒュ: "hyu", ヒョ: "hyo",
  ミャ: "mya", ミュ: "myu", ミョ: "myo",
  リャ: "rya", リュ: "ryu", リョ: "ryo",
  ギャ: "gya", ギュ: "gyu", ギョ: "gyo",
  ジャ: "ja", ジュ: "ju", ジョ: "jo",
  ビャ: "bya", ビュ: "byu", ビョ: "byo",
  ピャ: "pya", ピュ: "pyu", ピョ: "pyo",
  ファ: "fa", フィ: "fi", フェ: "fe", フォ: "fo",
  ヴァ: "va", ヴィ: "vi", ヴェ: "ve", ヴォ: "vo",
  ティ: "ti", ディ: "di", トゥ: "tu", ドゥ: "du",
  チェ: "che", ジェ: "je", シェ: "she",
  ウィ: "wi", ウェ: "we", ウォ: "wo",
};

const SINGLES: Record<string, string> = {
  ア: "a", イ: "i", ウ: "u", エ: "e", オ: "o",
  カ: "ka", キ: "ki", ク: "ku", ケ: "ke", コ: "ko",
  ガ: "ga", ギ: "gi", グ: "gu", ゲ: "ge", ゴ: "go",
  サ: "sa", シ: "shi", ス: "su", セ: "se", ソ: "so",
  ザ: "za", ジ: "ji", ズ: "zu", ゼ: "ze", ゾ: "zo",
  タ: "ta", チ: "chi", ツ: "tsu", テ: "te", ト: "to",
  ダ: "da", ヂ: "ji", ヅ: "zu", デ: "de", ド: "do",
  ナ: "na", ニ: "ni", ヌ: "nu", ネ: "ne", ノ: "no",
  ハ: "ha", ヒ: "hi", フ: "fu", ヘ: "he", ホ: "ho",
  バ: "ba", ビ: "bi", ブ: "bu", ベ: "be", ボ: "bo",
  パ: "pa", ピ: "pi", プ: "pu", ペ: "pe", ポ: "po",
  マ: "ma", ミ: "mi", ム: "mu", メ: "me", モ: "mo",
  ヤ: "ya", ユ: "yu", ヨ: "yo",
  ラ: "ra", リ: "ri", ル: "ru", レ: "re", ロ: "ro",
  ワ: "wa", ヲ: "wo", ン: "n",
  ヴ: "vu",
};

const VOWELS = new Set(["a", "i", "u", "e", "o"]);

export function katakanaToRomaji(reading: string): string {
  let result = "";
  let i = 0;
  while (i < reading.length) {
    const char = reading[i];

    if (char === "ー") {
      const lastVowel = [...result].reverse().find((c) => VOWELS.has(c));
      if (lastVowel) result += lastVowel;
      i += 1;
      continue;
    }

    if (char === "ッ") {
      const nextRomaji = romajiForOne(reading, i + 1);
      if (nextRomaji) {
        result += nextRomaji.romaji.startsWith("ch") ? "t" : nextRomaji.romaji[0];
      }
      i += 1;
      continue;
    }

    const match = romajiForOne(reading, i);
    if (match) {
      result += match.romaji;
      i += match.length;
    } else {
      // Not katakana (already-latin punctuation, etc.) — pass through.
      result += char;
      i += 1;
    }
  }
  return result;
}

function romajiForOne(reading: string, index: number): { romaji: string; length: number } | null {
  const twoChar = reading.slice(index, index + 2);
  if (DIGRAPHS[twoChar]) {
    return { romaji: DIGRAPHS[twoChar], length: 2 };
  }
  const oneChar = reading[index];
  if (SINGLES[oneChar]) {
    return { romaji: SINGLES[oneChar], length: 1 };
  }
  return null;
}
