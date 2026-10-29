import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Incident, IncidentPage, TimelineEvent } from '../core/api.models';
import { IncidentFilters } from './incident-merge';

const BASE = '/api/v1/incidents';

@Injectable({ providedIn: 'root' })
export class IncidentApi {
  private readonly http = inject(HttpClient);

  search(filters: IncidentFilters, cursor: string | null, limit = 50): Observable<IncidentPage> {
    let params = new HttpParams().set('limit', limit);
    if (filters.statuses.length > 0) {
      params = params.set('status', filters.statuses.join(','));
    }
    if (filters.severities.length > 0) {
      params = params.set('severity', filters.severities.join(','));
    }
    if (filters.assignee !== 'anyone') {
      params = params.set('assignee', filters.assignee);
    }
    if (filters.q.trim() !== '') {
      params = params.set('q', filters.q.trim());
    }
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<IncidentPage>(BASE, { params });
  }

  get(id: string): Observable<Incident> {
    return this.http.get<Incident>(`${BASE}/${id}`);
  }

  timeline(id: string): Observable<TimelineEvent[]> {
    return this.http.get<TimelineEvent[]>(`${BASE}/${id}/timeline`);
  }

  acknowledge(id: string): Observable<Incident> {
    return this.http.post<Incident>(`${BASE}/${id}/acknowledge`, null);
  }

  assign(id: string, assigneeId: string): Observable<Incident> {
    return this.http.post<Incident>(`${BASE}/${id}/assign`, { assigneeId });
  }

  resolve(id: string): Observable<Incident> {
    return this.http.post<Incident>(`${BASE}/${id}/resolve`, null);
  }

  comment(id: string, body: string): Observable<TimelineEvent> {
    return this.http.post<TimelineEvent>(`${BASE}/${id}/comments`, { body });
  }
}
