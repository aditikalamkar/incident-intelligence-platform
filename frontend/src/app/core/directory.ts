import { HttpClient } from '@angular/common/http';
import { effect, inject, Injectable, signal, untracked } from '@angular/core';
import { Service, User } from './api.models';
import { Auth } from './auth';

/**
 * Names for the IDs the API returns. Services are registered by incoming alerts, so an unknown
 * service ID means a new one: {@link noteServices} reloads the list when it sees one.
 */
@Injectable({ providedIn: 'root' })
export class Directory {
  private readonly http = inject(HttpClient);
  private readonly usersById = signal(new Map<string, User>());
  private readonly servicesById = signal(new Map<string, Service>());
  private loadingServices = false;

  readonly users = signal<User[]>([]);

  constructor() {
    const auth = inject(Auth);
    effect(() => {
      if (auth.signedIn()) {
        untracked(() => {
          this.loadUsers();
          this.loadServices();
        });
      }
    });
  }

  userName(id: string | null | undefined): string {
    if (!id) {
      return 'Nobody';
    }
    return this.usersById().get(id)?.name ?? 'Unknown user';
  }

  serviceName(id: string | null | undefined): string {
    return (id && this.servicesById().get(id)?.name) || 'Unknown service';
  }

  noteServices(ids: Iterable<string | null>): void {
    const known = this.servicesById();
    for (const id of ids) {
      if (id && !known.has(id)) {
        this.loadServices();
        return;
      }
    }
  }

  private loadUsers(): void {
    this.http.get<User[]>('/api/v1/users').subscribe((users) => {
      this.users.set(users);
      this.usersById.set(new Map(users.map((user) => [user.id, user])));
    });
  }

  private loadServices(): void {
    if (this.loadingServices) {
      return;
    }
    this.loadingServices = true;
    this.http.get<Service[]>('/api/v1/services').subscribe({
      next: (services) => this.servicesById.set(new Map(services.map((s) => [s.id, s]))),
      complete: () => (this.loadingServices = false),
      error: () => (this.loadingServices = false),
    });
  }
}
