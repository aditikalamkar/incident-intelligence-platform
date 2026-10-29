export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Incremental Server-Sent Events parser (https://html.spec.whatwg.org/#event-stream-interpretation).
 * Needed because the stream is read with fetch: the browser's EventSource cannot send the
 * Authorization header. Feed it decoded text chunks as they arrive; chunks may split lines anywhere.
 */
export class SseParser {
  private buffer = '';
  private eventName = '';
  private dataLines: string[] = [];

  push(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const messages: SseMessage[] = [];
    const lineBreak = /\r\n|\r|\n/g;
    let lineStart = 0;
    let match: RegExpExecArray | null;
    while ((match = lineBreak.exec(this.buffer)) !== null) {
      if (match[0] === '\r' && match.index === this.buffer.length - 1) {
        break; // may be the first half of a \r\n split across chunks
      }
      const message = this.processLine(this.buffer.slice(lineStart, match.index));
      if (message) {
        messages.push(message);
      }
      lineStart = lineBreak.lastIndex;
    }
    this.buffer = this.buffer.slice(lineStart);
    return messages;
  }

  private processLine(line: string): SseMessage | null {
    if (line === '') {
      return this.dispatch();
    }
    if (line.startsWith(':')) {
      return null; // comment, e.g. a heartbeat
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'event') {
      this.eventName = value;
    } else if (field === 'data') {
      this.dataLines.push(value);
    }
    return null;
  }

  private dispatch(): SseMessage | null {
    const message =
      this.dataLines.length > 0
        ? { event: this.eventName || 'message', data: this.dataLines.join('\n') }
        : null;
    this.eventName = '';
    this.dataLines = [];
    return message;
  }
}
