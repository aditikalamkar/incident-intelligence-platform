# ADR 0001: Modular monolith first

Status: accepted

## Context
Small team, early product, no validated customer load. Microservices would add operational cost without benefit.

## Decision
One Spring Boot deployment with package-level modules (identity, tenant, alerting, incident, escalation, notification, audit, integration, ai, common). PostgreSQL is the system of record. Kafka and Redis are added only when a concrete need appears.

## Consequences
Simple deployment and transactions across modules. Module boundaries must be enforced by convention (and later ArchUnit tests) to allow future extraction.
