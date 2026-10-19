import { useState } from 'react';

const LETTER_ROWS = ['1234567890', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
const SYMBOL_ROWS = ['1234567890', '!@#$%^&*()', '-_=+[]{}', `;'",.<>/?\\|` + '`~'];
const SHIFTED_DIGITS: Record<string, string> = {
  '1': '!', '2': '@', '3': '#', '4': '$', '5': '%',
  '6': '^', '7': '&', '8': '*', '9': '(', '0': ')',
};

/** Local pointer/touch keyboard. It is an input aid, not a substitute for endpoint security. */
export function CertificateVirtualKeyboard({ onInsert, onBackspace, onClose }: {
  onInsert: (character: string) => void;
  onBackspace: () => void;
  onClose: () => void;
}) {
  const [symbols, setSymbols] = useState(false);
  const [shift, setShift] = useState(false);
  const rows = symbols ? SYMBOL_ROWS : LETTER_ROWS;

  function insert(character: string) {
    onInsert(!symbols && shift ? SHIFTED_DIGITS[character] ?? character.toUpperCase() : character);
    if (shift) setShift(false);
  }

  return <div id="cert-virtual-keyboard" className="cert-virtual-keyboard" role="group" aria-label="가상키보드">
    {rows.map((row, index) => <div className="cert-keyboard-row" key={index}>
      {Array.from(row).map((character, position) => <button key={`${index}-${position}`} type="button"
        onMouseDown={event => event.preventDefault()} onClick={() => insert(character)}>
        {!symbols && shift ? SHIFTED_DIGITS[character] ?? character.toUpperCase() : character}
      </button>)}
    </div>)}
    <div className="cert-keyboard-row cert-keyboard-actions">
      <button type="button" aria-pressed={symbols} onClick={() => { setSymbols(!symbols); setShift(false); }}>{symbols ? '영문' : '기호'}</button>
      {!symbols && <button type="button" aria-pressed={shift} onClick={() => setShift(!shift)}>Shift</button>}
      <button type="button" onMouseDown={event => event.preventDefault()} onClick={() => onInsert(' ')}>Space</button>
      <button type="button" onMouseDown={event => event.preventDefault()} onClick={onBackspace}>지우기</button>
      <button type="button" onClick={onClose}>닫기</button>
    </div>
  </div>;
}
