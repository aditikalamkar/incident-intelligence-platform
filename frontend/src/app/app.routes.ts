import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { Login } from './login/login';

export const routes: Routes = [
  { path: 'login', component: Login, title: 'Sign in · OpsPilot' },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      {
        path: '',
        loadComponent: () => import('./incidents/incident-list').then((m) => m.IncidentList),
        title: 'Incidents · OpsPilot',
      },
      {
        path: 'incidents/:id',
        loadComponent: () => import('./incidents/incident-detail').then((m) => m.IncidentDetail),
        title: 'Incident · OpsPilot',
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
