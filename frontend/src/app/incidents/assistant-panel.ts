import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Investigation, TimelineEvent } from '../core/api.models';
import { Directory } from '../core/directory';
import { AgoPipe } from '../shared/ago.pipe';
import { Clock } from '../shared/clock';
import { errorMessage } from '../shared/errors';
import { IncidentApi } from './incident-api';
import { IncidentStream } from './incident-stream';

const INVESTIGATION_EVENTS: ReadonlySet<TimelineEvent['type']> = new Set([
  'INVESTIGATION_COMPLETED',
  'INVESTIGATION_FAILED',
]);

/**
 * The AI assistant on an incident's page: starts an investigation and shows the latest one, with links
 * to the timeline events, alerts and earlier incidents it relies on. Hidden when the server has no
 * assistant. A finished investigation arrives as a timeline event on the live stream.
 */
@Component({
  selector: 'app-assistant-panel',
  imports: [DatePipe, RouterLink, AgoPipe],
  templateUrl: './assistant-panel.html',
  styleUrl: './assistant-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssistantPanel {
  readonly incidentId = input.required<string>();
  /** The incident's timeline, to tell how much has happened since the investigation. */
  readonly timeline = input.required<TimelineEvent[]>();

  private readonly api = inject(IncidentApi);
  protected readonly directory = inject(Directory);
  protected readonly clock = inject(Clock);

  protected readonly enabled = signal(false);
  protected readonly investigation = signal<Investigation | null>(null);
  protected readonly starting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly sections = computed(() => {
    const result = this.investigation()?.result;
    if (!result) {
      return [];
    }
    return [
      { title: 'What the evidence shows', items: result.findings },
      { title: 'Suggested next steps', items: result.nextSteps },
    ].filter((section) => section.items.length > 0);
  });

  /** Timeline events after the assistant read the incident, apart from its own. */
  protected readonly newEvents = computed(() => {
    const asOf = this.investigation()?.result?.asOf;
    if (!asOf) {
      return 0;
    }
    const since = Date.parse(asOf);
    return this.timeline().filter(
      (event) => !INVESTIGATION_EVENTS.has(event.type) && Date.parse(event.createdAt) > since,
    ).length;
  });

  private loading: Subscription | undefined;

  constructor() {
    effect(() => {
      const id = this.incidentId();
      untracked(() => {
        this.investigation.set(null);
        this.error.set(null);
        if (this.enabled()) {
          this.load(id);
        }
      });
    });
    this.api
      .assistant()
      .pipe(takeUntilDestroyed())
      .subscribe((status) => {
        this.enabled.set(status.enabled);
        if (status.enabled) {
          this.load(this.incidentId());
        }
      });
    inject(IncidentStream)
      .events.pipe(takeUntilDestroyed())
      .subscribe((event) => {
        if (!this.enabled()) {
          return;
        }
        if (
          event.type === 'reload' ||
          (event.update.incident.id === this.incidentId() &&
            INVESTIGATION_EVENTS.has(event.update.event.type))
        ) {
          this.load(this.incidentId());
        }
      });
  }

  protected investigate(): void {
    if (this.starting()) {
      return;
    }
    const id = this.incidentId();
    this.starting.set(true);
    this.error.set(null);
    this.api.investigate(id).subscribe({
      next: (investigation) => {
        this.starting.set(false);
        if (id === this.incidentId()) {
          this.accept(investigation);
        }
      },
      error: (error: unknown) => {
        this.starting.set(false);
        this.error.set(errorMessage(error));
      },
    });
  }

  /** Scrolls the timeline to an event the assistant cited and focuses it, which highlights it. */
  protected showEvent(eventId: string): void {
    const element = document.getElementById(`event-${eventId}`);
    if (!element) {
      return;
    }
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    element.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' });
    element.focus({ preventScroll: true });
  }

  private load(id: string): void {
    this.loading?.unsubscribe();
    this.loading = this.api.latestInvestigation(id).subscribe({
      next: (investigation) => this.accept(investigation),
      error: (error: unknown) => this.error.set(errorMessage(error)),
    });
  }

  /**
   * Responses can arrive out of order (the start request, a load after the completion event):
   * keep the newest investigation, and never go back from finished to running.
   */
  private accept(next: Investigation | null): void {
    const current = this.investigation();
    if (!next || !current) {
      this.investigation.set(next ?? current);
    } else if (next.id === current.id) {
      if (!(next.status === 'RUNNING' && current.status !== 'RUNNING')) {
        this.investigation.set(next);
      }
    } else if (Date.parse(next.createdAt) >= Date.parse(current.createdAt)) {
      this.investigation.set(next);
    }
  }
}
