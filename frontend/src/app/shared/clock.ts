import { Injectable, signal } from '@angular/core';

/** The current time, updated every 30 seconds so "5 min ago" labels stay right. */
@Injectable({ providedIn: 'root' })
export class Clock {
  readonly now = signal(Date.now());

  constructor() {
    setInterval(() => this.now.set(Date.now()), 30_000);
  }
}
