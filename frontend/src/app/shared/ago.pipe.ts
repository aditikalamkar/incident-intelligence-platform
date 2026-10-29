import { Pipe, PipeTransform } from '@angular/core';

/** {@code openedAt | ago: clock.now()} → "just now", "12 min ago", "3 h ago", "2 d ago". */
@Pipe({ name: 'ago' })
export class AgoPipe implements PipeTransform {
  transform(iso: string | null | undefined, now: number): string {
    if (!iso) {
      return '';
    }
    const seconds = Math.max(0, (now - Date.parse(iso)) / 1000);
    if (seconds < 45) {
      return 'just now';
    }
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) {
      return `${minutes} min ago`;
    }
    const hours = Math.round(minutes / 60);
    if (hours < 24) {
      return `${hours} h ago`;
    }
    return `${Math.round(hours / 24)} d ago`;
  }
}
