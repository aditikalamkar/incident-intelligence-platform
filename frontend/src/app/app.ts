import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { Auth } from './core/auth';
import { IncidentStream } from './incidents/incident-stream';

const STATUS_LABELS = {
  offline: 'Offline',
  connecting: 'Connecting…',
  live: 'Live',
  reconnecting: 'Reconnecting…',
} as const;

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly auth = inject(Auth);
  protected readonly stream = inject(IncidentStream);
  protected readonly streamLabel = computed(() => STATUS_LABELS[this.stream.status()]);
}
