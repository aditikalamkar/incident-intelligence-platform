import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { debounceTime, Subscription } from 'rxjs';
import { Incident, IncidentPage, IncidentStatus, Severity, SEVERITIES } from '../core/api.models';
import { Auth } from '../core/auth';
import { Directory } from '../core/directory';
import { AgoPipe } from '../shared/ago.pipe';
import { Clock } from '../shared/clock';
import { errorMessage } from '../shared/errors';
import { IncidentApi } from './incident-api';
import { IncidentListFilters } from './incident-list-filters';
import { appendPage, IncidentFilters, mergeUpdate } from './incident-merge';
import { IncidentStream } from './incident-stream';

type View = 'open' | IncidentStatus | 'all';

const VIEW_STATUSES: Record<View, IncidentStatus[]> = {
  open: ['TRIGGERED', 'ACKNOWLEDGED'],
  TRIGGERED: ['TRIGGERED'],
  ACKNOWLEDGED: ['ACKNOWLEDGED'],
  RESOLVED: ['RESOLVED'],
  all: [],
};

const HIGHLIGHT_MILLIS = 2500;

@Component({
  selector: 'app-incident-list',
  imports: [RouterLink, AgoPipe],
  templateUrl: './incident-list.html',
  styleUrl: './incident-list.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IncidentList {
  private readonly api = inject(IncidentApi);
  private readonly auth = inject(Auth);
  protected readonly directory = inject(Directory);
  protected readonly clock = inject(Clock);
  protected readonly filters = inject(IncidentListFilters).filters;
  protected readonly severities = SEVERITIES;

  protected readonly items = signal<Incident[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Rows that just changed, briefly highlighted. */
  protected readonly highlighted = signal<ReadonlySet<string>>(new Set());
  protected readonly searchText = signal(this.filters().q);
  protected readonly view = computed<View>(() => {
    const statuses = [...this.filters().statuses].sort().join();
    const match = (Object.keys(VIEW_STATUSES) as View[]).find(
      (view) => [...VIEW_STATUSES[view]].sort().join() === statuses,
    );
    return match ?? 'all';
  });

  /** Live updates that arrive while a page is loading; applied once it has loaded. */
  private pending: Incident[] | null = null;
  private loadSubscription: Subscription | undefined;

  constructor() {
    effect(() => {
      const filters = this.filters();
      untracked(() => this.load(filters, null));
    });
    toObservable(this.searchText)
      .pipe(debounceTime(300), takeUntilDestroyed())
      .subscribe((q) => {
        if (q !== this.filters().q) {
          this.filters.update((filters) => ({ ...filters, q }));
        }
      });
    inject(IncidentStream)
      .events.pipe(takeUntilDestroyed())
      .subscribe((event) =>
        event.type === 'reload' ? this.load(this.filters(), null) : this.apply(event.update.incident),
      );
  }

  protected setView(view: string): void {
    this.filters.update((filters) => ({ ...filters, statuses: VIEW_STATUSES[view as View] }));
  }

  protected setAssignee(assignee: string): void {
    this.filters.update((filters) => ({
      ...filters,
      assignee: assignee as IncidentFilters['assignee'],
    }));
  }

  protected toggleSeverity(severity: Severity): void {
    this.filters.update((filters) => ({
      ...filters,
      severities: filters.severities.includes(severity)
        ? filters.severities.filter((s) => s !== severity)
        : [...filters.severities, severity],
    }));
  }

  protected loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor && !this.loading()) {
      this.load(this.filters(), cursor);
    }
  }

  /** @param cursor null loads the first page and replaces the list */
  private load(filters: IncidentFilters, cursor: string | null): void {
    this.loadSubscription?.unsubscribe();
    this.pending = [];
    this.loading.set(true);
    this.error.set(null);
    this.loadSubscription = this.api.search(filters, cursor).subscribe({
      next: (page) => this.loaded(filters, cursor === null ? [] : this.items(), page),
      error: (error: unknown) => {
        this.pending = null;
        this.loading.set(false);
        this.error.set(errorMessage(error));
      },
    });
  }

  private loaded(filters: IncidentFilters, before: Incident[], page: IncidentPage): void {
    let items = appendPage(before, page.items);
    for (const update of this.pending ?? []) {
      items = mergeUpdate(items, update, filters, this.me(), page.nextCursor === null);
    }
    this.pending = null;
    this.items.set(items);
    this.nextCursor.set(page.nextCursor);
    this.loading.set(false);
    this.directory.noteServices(items.map((incident) => incident.serviceId));
  }

  private apply(incident: Incident): void {
    if (this.pending) {
      this.pending.push(incident);
      return;
    }
    const before = this.items();
    const after = mergeUpdate(before, incident, this.filters(), this.me(), this.nextCursor() === null);
    if (after !== before) {
      this.items.set(after);
      this.highlight(incident.id);
      this.directory.noteServices([incident.serviceId]);
    }
  }

  private highlight(id: string): void {
    this.highlighted.update((ids) => new Set(ids).add(id));
    setTimeout(() => {
      this.highlighted.update((ids) => {
        const remaining = new Set(ids);
        remaining.delete(id);
        return remaining;
      });
    }, HIGHLIGHT_MILLIS);
  }

  private me(): string | null {
    return this.auth.user()?.id ?? null;
  }
}
