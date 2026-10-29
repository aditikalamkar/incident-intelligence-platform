import { Injectable, signal } from '@angular/core';
import { IncidentFilters } from './incident-merge';

/** Kept outside the list page so the filters survive opening an incident and coming back. */
@Injectable({ providedIn: 'root' })
export class IncidentListFilters {
  readonly filters = signal<IncidentFilters>({
    statuses: ['TRIGGERED', 'ACKNOWLEDGED'],
    severities: [],
    assignee: 'anyone',
    q: '',
  });
}
