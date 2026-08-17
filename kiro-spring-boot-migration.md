# Kiro Spring Boot Migration Guide

This document outlines considerations if the Android AI Assistant backend is
ever migrated from FastAPI (Python) to Spring Boot (Kotlin/JVM), to align the
backend tech stack with the Android client.

## Current Stack

| Component | Technology |
|---|---|
| API Framework | FastAPI (Python 3.11) |
| ORM | SQLAlchemy 2.x (async) |
| Migrations | Alembic |
| Task Queue | Celery + Redis |
| Vector Store | ChromaDB |
| Auth | PyJWT + bcrypt |
| Container | Docker (python:3.11-slim) |

## Spring Boot Equivalent Stack

| Component | Spring Boot Equivalent |
|---|---|
| API Framework | Spring Boot 3.x + Spring WebFlux (reactive) |
| ORM | Spring Data JPA + Hibernate / R2DBC (reactive) |
| Migrations | Flyway or Liquibase |
| Task Queue | Spring Batch + Redis / RabbitMQ |
| Vector Store | ChromaDB Java client or pgvector |
| Auth | Spring Security + JJWT |
| Container | eclipse-temurin:21-jre-alpine |

## Migration Checklist

- [ ] Port SQLAlchemy models to JPA `@Entity` classes
- [ ] Rewrite Alembic migrations to Flyway SQL scripts
- [ ] Port FastAPI routers to `@RestController` classes
- [ ] Replace Pydantic schemas with Kotlin data classes + Bean Validation
- [ ] Port Celery tasks to `@Scheduled` / Spring Batch jobs
- [ ] Port JWT handler to Spring Security filter chain
- [ ] Port bcrypt password hashing to `BCryptPasswordEncoder`
- [ ] Port ChromaDB Python client calls to HTTP client (OkHttp / WebClient)
- [ ] Port pytest tests to JUnit 5 + MockK
- [ ] Update Docker base image to eclipse-temurin:21-jre-alpine
- [ ] Update CI/CD workflows (backend-ci.yml) for Gradle/Maven build

## Notes

- The Android client already uses Kotlin + Hilt; sharing domain models via a
  Kotlin Multiplatform (KMP) module is feasible post-migration.
- FastAPI's async model maps naturally to Spring WebFlux; avoid Spring MVC
  (blocking) for equivalent throughput.
- Differential privacy (Laplace noise) must be re-implemented in Kotlin;
  consider the `opendp` Java bindings.
