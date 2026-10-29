import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { forkJoin, Observable, Subscription } from 'rxjs';
import { Incident, IncidentUpdate, TimelineEvent } from '../core/api.models';
import { Auth } from '../core/auth';
import { Directory } from '../core/directory';
import { AgoPipe } from '../shared/ago.pipe';
import { Clock } from '../shared/clock';
import { errorMessage } from '../shared/errors';
import { AssistantPanel } from './assistant-panel';
import { IncidentApi } from './incident-api';
import { isOlder } from './incident-merge';
import { IncidentStream } from './incident-stream';
import { describeEvent } from './timeline-text';

const MAX_COMMENT_LENGTH = 5000;

@Component({
  selector: 'app-incident-detail',
  imports: [RouterLink, DatePipe, AgoPipe, AssistantPanel],
  templateUrl: './incident-detail.html',
  styleUrl: './incident-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IncidentDetail {
  /** From the route, /incidents/:id */
  readonly id = input.required<string>();

  private readonly api = inject(IncidentApi);
  private readonly title = inject(Title);
  protected readonly auth = inject(Auth);
  protected readonly directory = inject(Directory);
  protected readonly clock = inject(Clock);
  protected readonly maxCommentLength = MAX_COMMENT_LENGTH;

  protected readonly incident = signal<Incident | null>(null);
  protected readonly timeline = signal<TimelineEvent[]>([]);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly comment = signal('');

  /** Live updates that arrive while loading; applied once loaded. */
  private pending: IncidentUpdate[] | null = null;
  private loadSubscription: Subscription | undefined;

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
    inject(IncidentStream)
      .events.pipe(takeUntilDestroyed())
      .subscribe((event) =>
        event.type === 'reload' ? this.load(this.id()) : this.apply(event.update),
      );
  }

  protected describe(event: TimelineEvent): string {
    return describeEvent(event, (id) => this.directory.userName(id));
  }

  protected commentBody(event: TimelineEvent): string | null {
    const body = event.details?.['body'];
    return event.type === 'COMMENT' && typeof body === 'string' ? body : null;
  }

  protected acknowledge(): void {
    this.act(this.api.acknowledge(this.id()));
  }

  protected assign(userId: string): void {
    if (userId) {
      this.act(this.api.assign(this.id(), userId));
    }
  }

  protected resolve(): void {
    this.act(this.api.resolve(this.id()));
  }

  protected addComment(): void {
    const body = this.comment().trim();
    if (!body || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.api.comment(this.id(), body).subscribe({
      next: (event) => {
        this.addEvent(event);
        this.comment.set('');
        this.busy.set(false);
      },
      error: (error: unknown) => this.failed(error),
    });
  }

  private act(request: Observable<Incident>): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    request.subscribe({
      next: (incident) => {
        this.applyIncident(incident);
        this.busy.set(false);
        // The live stream brings the new timeline events; reload in case it is disconnected
        this.api.timeline(this.id()).subscribe((events) => events.forEach((e) => this.addEvent(e)));
      },
      error: (error: unknown) => this.failed(error),
    });
  }

  private failed(error: unknown): void {
    this.busy.set(false);
    if (error instanceof HttpErrorResponse && error.status === 409) {
      this.actionError.set('Someone else just changed this incident. Showing the latest state.');
      this.load(this.id());
    } else {
      this.actionError.set(errorMessage(error));
    }
  }

  private load(id: string): void {
    this.loadSubscription?.unsubscribe();
    this.pending = [];
    this.loadSubscription = forkJoin([this.api.get(id), this.api.timeline(id)]).subscribe({
      next: ([incident, events]) => {
        this.incident.set(incident);
        this.timeline.set(events);
        this.loadError.set(null);
        for (const update of this.pending ?? []) {
          this.applyIncident(update.incident);
          this.addEvent(update.event);
        }
        this.pending = null;
        this.title.setTitle(`${incident.title} · OpsPilot`);
        this.directory.noteServices([incident.serviceId]);
      },
      error: (error: unknown) => {
        this.pending = null;
        this.loadError.set(
          error instanceof HttpErrorResponse && error.status === 404
            ? 'This incident does not exist, or belongs to another organisation.'
            : errorMessage(error),
        );
      },
    });
  }

  private apply(update: IncidentUpdate): void {
    if (update.incident.id !== this.id()) {
      return;
    }
    if (this.pending) {
      this.pending.push(update);
      return;
    }
    this.applyIncident(update.incident);
    this.addEvent(update.event);
  }

  private applyIncident(incident: Incident): void {
    const current = this.incident();
    if (!current || !isOlder(incident, current)) {
      this.incident.set(incident);
    }
  }

  private addEvent(event: TimelineEvent): void {
    this.timeline.update((events) =>
      events.some((e) => e.id === event.id)
        ? events
        : [...events, event].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt)),
    );
  }
}
