/**
 * CSV for spreadsheet use: RFC 4180 quoting, CRLF line ends, a UTF-8 byte-order mark so Excel reads
 * names with accents correctly. A cell starting with =, +, -, @, tab or carriage return is prefixed
 * with an apostrophe, so a customer named "=HYPERLINK(...)" cannot become a formula when opened.
 */
/** Excel's hint that the file is UTF-8. */
const BOM = String.fromCharCode(0xfeff);

export function toCsv(header: string[], rows: (string | number | null | undefined)[][]): string {
  const lines = [header, ...rows].map((cells) => cells.map(cell).join(','));
  return BOM + lines.join('\r\n') + '\r\n';
}

function cell(value: string | number | null | undefined): string {
  if (value === null || value === undefined) {
    return '';
  }
  let text = String(value);
  if (typeof value === 'string' && /^[=+\-@\t\r]/.test(text)) {
    text = "'" + text;
  }
  return /[",\r\n]/.test(text) || text !== text.trim()
    ? '"' + text.replace(/"/g, '""') + '"'
    : text;
}

/** Offers {@code content} as a file download named {@code fileName}. */
export function downloadCsv(fileName: string, content: string): void {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}
