import { toCsv } from './csv';

const BOM = String.fromCharCode(0xfeff);

describe('toCsv', () => {
  it('quotes commas, quotes and line breaks, with CRLF rows and a BOM', () => {
    const csv = toCsv(
      ['Name', 'Note'],
      [
        ['Wanjiku, Mary', 'says "hi"'],
        ['Otieno', 'two\nlines'],
      ],
    );
    expect(csv).toBe(BOM + 'Name,Note\r\n"Wanjiku, Mary","says ""hi"""\r\nOtieno,"two\nlines"\r\n');
  });

  it('defuses spreadsheet formulas in text cells', () => {
    const csv = toCsv(['Name'], [['=HYPERLINK("http://x")'], ['+254712345678'], ['@cmd'], ['-1']]);
    expect(csv.split('\r\n').slice(1, 5)).toEqual([
      '"\'=HYPERLINK(""http://x"")"',
      "'+254712345678",
      "'@cmd",
      "'-1",
    ]);
  });

  it('leaves numbers and empty cells alone', () => {
    expect(toCsv(['A', 'B', 'C'], [[-5, null, undefined]])).toBe(BOM + 'A,B,C\r\n-5,,\r\n');
  });
});
