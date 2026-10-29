import { SseParser } from './sse-parser';

describe('SseParser', () => {
  it('parses named events as the server sends them', () => {
    const parser = new SseParser();

    expect(parser.push('event:ready\ndata:{}\n\nevent:incident\ndata:{"a":1}\n\n')).toEqual([
      { event: 'ready', data: '{}' },
      { event: 'incident', data: '{"a":1}' },
    ]);
  });

  it('handles events split anywhere across chunks', () => {
    const parser = new SseParser();
    const text = 'event:incident\ndata:{"title":"café 🚀"}\n\n';
    const messages = [...text].flatMap((char) => parser.push(char));

    expect(messages).toEqual([{ event: 'incident', data: '{"title":"café 🚀"}' }]);
  });

  it('treats a CRLF split across two chunks as one line break', () => {
    const parser = new SseParser();

    expect(parser.push('data:x\r')).toEqual([]);
    expect(parser.push('\n\r\n')).toEqual([{ event: 'message', data: 'x' }]);
  });

  it('ignores heartbeat comments and unknown fields', () => {
    const parser = new SseParser();

    expect(parser.push(':keepalive\n\nid:7\nretry:1000\n\n')).toEqual([]);
  });

  it('joins multi-line data and strips one leading space', () => {
    const parser = new SseParser();

    expect(parser.push('event: note\ndata: line one\ndata:  line two\n\n')).toEqual([
      { event: 'note', data: 'line one\n line two' },
    ]);
  });

  it('waits for the blank line before dispatching', () => {
    const parser = new SseParser();

    expect(parser.push('event:ready\ndata:{}\n')).toEqual([]);
    expect(parser.push('\n')).toEqual([{ event: 'ready', data: '{}' }]);
  });
});
