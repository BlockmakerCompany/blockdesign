# ROADMAP: Remedación de Auditoría de Seguridad (Criptored — 27 julio 2026)

## Contexto

La auditoría de seguridad realizada por Criptored identificó **35 hallazgos** agrupados en dos niveles y dos informes:
- **Informe 1 (T1-)**: 30 hallazgos (16 Nivel 1 + 14 Nivel 2)
- **Informe 2 (T2-)**: 5 hallazgos adicionales (2 Nivel 1 + 3 Nivel 2)
  - Nivel 1: 1 crítica, 1 alta
  - Nivel 2: 1 alta, 1 media, 1 baja

Este documento es el **tablero de progreso** de alto nivel. Cada entrada requerirá su propio plan detallado de implementación.

## Estados

| Estado | Significado |
|--------|-------------|
| `pending` | No se ha empezado a trabajar |
| `planning` | Se está diseñando el plan de implementación |
| `in-progress` | Implementación en curso |
| `done` | Completado y verificado |
| `wont-fix` | No se solucionará (ver anotación) |
| `deferred` | Aplazado (ver anotación) |

## Procedimiento canónico para cada tarea

Cada tarea debe seguir estos pasos en orden:

1. **Plan** — Cargar la skill `planner` y producir un plan de implementación estructurado (Contexto, Módulos afectados, Enfoque, Riesgos, Testing). Guardar en `.opencode/plans/`.
2. **Leer docs de testing** — Leer `mem:testing` y `mem:backend/core` para convenciones de testing antes de escribir código.
3. **TDD — RED** — Escribir un test que falle reproduciendo el issue o validando el comportamiento esperado.
4. **TDD — GREEN** — Implementar la corrección mínima para que el test pase.
5. **TDD — REFACTOR** — Ejecutar la suite completa de tests (`clojure -M:dev:test` desde `backend/`). Verificar 0 fallos.
6. **Lint & Format** — Ejecutar `clj-kondo --lint ../common/src/ src/` y `cljfmt check src/ test/` (o `cljfmt fix` si es necesario). Corregir cualquier problema antes de hacer commit.
7. **Commit** — Seguir las convenciones de commit. Hacer amend si se corrigió formato después del commit inicial.

## Convenciones de commit

Each fix must be committed at the end of its TDD cycle (RED → GREEN → REFACTOR). Commit messages must follow these rules:

- **Calm, professional tone** — no alarming words (e.g., "vulnerability", "security flaw", "critical breach").
- **No references to the audit report** — do not mention "Criptored", "audit", or finding IDs in commit messages.
- **Use `:bug:` emoji** for security fixes (convention, not severity indicator).
- **Describe the technical change**, not the security implication. Example: `:bug: Close schema for update-profile-props to reject unknown keys` — NOT `:bug: Fix critical security vulnerability in profile update`.

## Distribución por prioridad de remediación

Según el propio informe, el orden recomendado es:
1. Cerrar acceso no autenticado a ficheros de media (T2-N1-01)
2. Cerrar Mass Assignment de suscripción (T1-N1-16)
3. Corregir BOLA de escritura y creación (T1-N1-06, T1-N1-13)
4. Abordar BOLA de fuga de contenido y metadatos (T1-N1-08, T1-N2-02, T1-N2-07)
5. Mitigar vectores de DoS en importación y almacenamiento (T1-N1-07, T1-N1-09, T1-N1-10, T1-N2-01, T2-N1-02, T2-N2-03)
6. Reforzar validaciones de entrada y gestión de errores

## Notas sobre agrupación

- **Entrada 1** (T2-N1-01) es la más crítica — realiza el riesgo de T1-N2-00 (UUIDs predecibles) y habilita T2-N2-02.
- **Entradas 7 y 8** (T1-N2-02, T1-N2-07) comparten causa raíz y remediación — un único cambio en `assemble-chunks` cierra ambas.
- **Entradas 21 y 22** (T1-N1-01, T1-N1-02) comparten patrón de oráculo — misma estrategia de remediación.
- **Entradas 11-16** (T1-N1-07, T1-N1-09, T1-N1-10, T1-N2-01, T2-N1-02, T2-N2-03) son todas DoS — pueden abordarse como planes unificados de hardening.
- **Entradas 21-27** comparten patrón de fuga de información por respuestas no normalizadas.
- **Entrada 35** (T1-N2-00) es un multiplicador de riesgo transversal — su verificación puede afectar la prioridad del resto.

## Dependencias entre entradas

```
T2-N1-01 (P0-1) → máxima prioridad, realiza riesgo de T1-N2-00; habilita T2-N2-02
T1-N1-16 (P0-2) → independiente
T1-N1-15 (P0-3) → T1-N2-10 (P0-4) es ortogonal pero se suma
T1-N1-06 (P1-5) → depende de entender check-edition-permissions!
T1-N1-13 (P1-6) → independiente
T1-N2-02 + T1-N2-07 (P1-7,8) → misma raíz, resolver juntos
T1-N1-08 (P2-9) → relacionado con T1-N1-06 (mismo parser v3)
T1-N1-14 (P2-10) → relacionado con T1-N1-13 (mismo módulo webhooks)
T1-N1-07+T1-N1-09+T1-N1-10+T1-N2-01+T2-N1-02+T2-N2-03 (P3) → todos DoS, flujos distintos
T2-N2-02 (P4-20) → dependiente de T2-N1-01 (acceso no autenticado a assets)
T1-N2-13 (P4-17) → independiente, módulo assets
T1-N2-00 (P8-35) → si se confirma predicción, re-prioritizar BOLA todos
```

## Módulo principal afectado

La inmensa mayoría de hallazgos afectan al **backend** (`backend/`), con algunos tocando **common** (`common/`) para schemas y validaciones compartidas, y **frontend** para T1-N1-03, T1-N2-09, T1-N2-13, T2-N2-02 y T2-N2-03. El hallazgo T2-N1-01 toca tanto backend (HTTP layer) como la lógica de assets.

---

## Tablero de progreso

### P0 — Crítico / Modelo de negocio

| # | Entrada | Hallazgos | Severidad | Estado | Anotación | Referencia |
|---|---------|-----------|-----------|--------|-----------|------------|
| 1 | Divulgación no autenticada de ficheros de media | T2-N1-01 | **Crítica** | `done` | Fix: require file read permissions in generic-handler, return 404 on denial | [T2-N1-01](./REPORT-2.md#L33) |
| 2 | Mass Assignment: bypass de suscripción | T1-N1-16 | **Crítica** | `done` | Fix: closed schema + denylist | [T1-N1-16](./REPORT-1.md#L574) |
| 3 | Bypass de control de asientos | T1-N1-15 | **Alta** | `wont-fix` | Not a security vulnerability. `quotes` is an operational safeguard (not enforced in prod), not a billing enforcement mechanism. Seat/billing management is Nitrate's responsibility (external system). The real vulnerability was T1-N1-16 (now fixed). | [T1-N1-15](./REPORT-1.md#L551) |
| 4 | Condición de carrera TOCTOU en cuotas | T1-N2-10 | **Media** | `wont-fix` | By design. The quote system is a lightweight abuse-detection mechanism, not precise billing enforcement. Some race condition is acceptable. Moving all callers into large transactions would degrade performance and increase lock contention for no real security benefit. | [T1-N2-10](./REPORT-1.md#L988) |

### P1 — BOLA/IDOR de escritura (integridad)

| # | Entrada | Hallazgos | Severidad | Estado | Anotación | Referencia |
|---|---------|-----------|-----------|--------|-----------|------------|
| 5 | BOLA sobrescritura de fichero ajeno (import-binfile) | T1-N1-06 | **Alta** | `done` | Fix: closed schema + removed file-id parameter | [T1-N1-06](./REPORT-1.md#L173) |
| 6 | Bypass autorización create-webhook | T1-N1-13 | **Alta** | `done` | Fix: use team role check only, no creator-id override for creation. Lint & fmt verified. | [T1-N1-13](./REPORT-1.md#L487) |
| 7 | BOLA en assemble-chunks (robo de subidas) | T1-N2-02 | **Media** | `done` | Fix: scope session lookup to profile-id in assemble-chunks | [T1-N2-02](./REPORT-1.md#L672) |
| 8 | BOLA en create-font-variant (misma raíz que T1-N2-02) | T1-N2-07 | **Media** | `done` | Fix: validate font-id team ownership before insert | [T1-N2-07](./REPORT-1.md#L794) |

### P2 — BOLA/IDOR de lectura (fuga de datos)

| # | Entrada | Hallazgos | Severidad | Estado | Anotación | Referencia |
|---|---------|-----------|-----------|--------|-----------|------------|
| 9 | BOLA inyección de relaciones de librería | T1-N1-08 | **Media** | `done` | Fix: validate library belongs to same team in link/unlink/sync handlers | [T1-N1-08](./REPORT-1.md#L302) |
| 10 | Persistencia webhooks tras expulsión | T1-N1-14 | **Media** | `done` | Fix: remove creator-id fallback from webhook permissions. Webhooks are NOT deleted on member removal (team owns them). `can-edit` comes from team role only — removed users get `:not-found`. | [T1-N1-14](./REPORT-1.md#L511) |

### P3 — DoS / Agotamiento de recursos

| # | Entrada | Hallazgos | Severidad | Estado | Anotación | Referencia |
|---|---------|-----------|-----------|--------|-----------|------------|
| 11 | DoS memoria en parser v1 (read-obj!) | T1-N1-07 | **Baja** | `done` | Fix: add max-object-size guard to read-obj! | [T1-N1-07](./REPORT-1.md#L276) |
| 12 | Zip bomb en import-storage-objects | T1-N1-09 | **Media** | `done` | Fix: configurable entry count + per-object size limits | [T1-N1-09](./REPORT-1.md#L374) |
| 13 | Agotamiento pool de conexiones (ZIP sin límite) | T1-N1-10 | **Alta** | `done` | Fix: add climit with 4 global + 1 per-profile permits | [T1-N1-10](./REPORT-1.md#L398) |
| 14 | Recursión sin límite en lector Fressian | T1-N2-01 | **Baja** | `done` | Fix: add 128-level depth bound to read-object! | [T1-N2-01](./REPORT-1.md#L651) |
| 15 | DoS persistente por ausencia de cuota de almacenamiento | T2-N1-02 | **Alta** | `done` | Fix: add media-storage-bytes-per-team quote (20 GiB default), covers file_media_object + team_font_variant storage | [T2-N1-02](./REPORT-2.md#L91) |
| 16 | DoS en pool de exportación (bounding box sin límite) | T2-N2-03 | **Alta** | `done` | Fix: add max-export-dimension validation in calculate-dimensions, reject exports exceeding 100000 units | [T2-N2-03](./REPORT-2.md#L235) |

### P4 — Upload / XSS / Inyección

| # | Entrada | Hallazgos | Severidad | Estado | Anotación | Referencia |
|---|---------|-----------|-----------|--------|-----------|------------|
| 17 | XSS almacenado vía upload-tempfile | T1-N2-13 | **Alta/Media** | `done` | Fix: validate content-type on upload-tempfile and upload-org-logo, add Content-Disposition: attachment for non-public buckets | [T1-N2-13](./REPORT-1.md#L1056) |
| 18 | Markdown injection hacia Mattermost | T1-N2-11 | **Media-Alta** | `done` | Internal-only feature (not user-accessible, near-zero severity). Fix: escape-markdown on user-controlled fields (:hint, :href) before constructing Mattermost notification messages. | [T1-N2-11](./REPORT-1.md#L1011) |
| 19 | Bypass skip-validate en update-file | T1-N2-12 | **Media** | `wont-fix` | Debugging tool for repair operations. If users break their own files via API, it's not our responsibility. | [T1-N2-12](./REPORT-1.md#L1035) |
| 20 | XSS almacenado vía SVG sin sanear | T2-N2-02 | **Media** | `done` | Fix: sanitize SVG on upload, remove script tags, event handlers, javascript: URLs, and foreignObject elements | [T2-N2-02](./REPORT-2.md#L182) |

### P5 — Enumeración / Fuga de información

| # | Entrada | Hallazgos | Severidad | Estado | Anotación | Referencia |
|---|---------|-----------|-----------|--------|-----------|------------|
| 21 | Enumeración de usuarios en registro | T1-N1-01 | **Baja** | `wont-fix` | Design decision. Many services reveal email existence; not considered worth implementing. | [T1-N1-01](./REPORT-1.md#L66) |
| 22 | Enumeración de usuarios en cambio de correo | T1-N1-02 | **Baja** | `wont-fix` | Design decision. Many services reveal email existence; not considered worth implementing. | [T1-N1-02](./REPORT-1.md#L91) |
| 23 | Oráculo de existencia de ficheros (create-file) | T1-N2-04 | **Baja** | `done` | Fix: capture unique constraint violation in insert-file! and return normalized :not-found error | [T1-N2-04](./REPORT-1.md#L717) |
| 24 | Enumeración de recursos en múltiples endpoints | T1-N2-05 | **Baja** | `done` | Fix: add permission checks to WebSocket subscribe-file and subscribe-team handlers | [T1-N2-05](./REPORT-1.md#L743) |
| 25 | Divulgación excesiva en report.txt del cliente | T1-N2-09 | **Baja** | `pending` | | [T1-N2-09](./REPORT-1.md#L860) |
| 26 | Eco de entrada sin filtrar | T1-N1-04 | **Baja** | `pending` | | [T1-N1-04](./REPORT-1.md#L135) |
| 27 | Respuestas de validación excesivamente verbosas | T2-N2-01 | **Baja** | `pending` | | [T2-N2-01](./REPORT-2.md#L154) |

### P6 — Validación de entrada / Robustez

| # | Entrada | Hallazgos | Severidad | Estado | Anotación | Referencia |
|---|---------|-----------|-----------|--------|-----------|------------|
| 28 | Validación de contraseña solo en frontend | T1-N1-03 | **Media** | `done` | Fix: add backend password validation (min 8 chars, 1 uppercase, 1 lowercase, 1 digit, 1 special char, common password dictionary check via Passay) | [T1-N1-03](./REPORT-1.md#L114) |
| 29 | Validación de tipo en objetos de componente | T1-N1-11 | **Baja** | `pending` | | [T1-N1-11](./REPORT-1.md#L435) |
| 30 | Selección de parser por metadata del cliente | T1-N2-03 | **Baja** | `pending` | | [T1-N2-03](./REPORT-1.md#L696) |
| 31 | Ausencia de límite inferior en total-chunks | T1-N2-06 | **Baja** | `pending` | | [T1-N2-06](./REPORT-1.md#L772) |

### P7 — Email abuse / Rate limiting

| # | Entrada | Hallazgos | Severidad | Estado | Anotación | Referencia |
|---|---------|-----------|-----------|--------|-----------|------------|
| 32 | Email bombing por invitaciones ilimitadas | T1-N1-12 | **Media** | `pending` | | [T1-N1-12](./REPORT-1.md#L456) |
| 33 | Acumulación sin límite de invitaciones de organización | T1-N2-08 | **Baja** | `pending` | | [T1-N2-08](./REPORT-1.md#L827) |
| 34 | Automatización de creación de cuentas | T1-N1-05 | **Informativa** | `pending` | | [T1-N1-05](./REPORT-1.md#L154) |

### P8 — Transversal / Infraestructura

| # | Entrada | Hallazgos | Severidad | Estado | Anotación | Referencia |
|---|---------|-----------|-----------|--------|-----------|------------|
| 35 | UUID potencialmente predecibles | T1-N2-00 | **A verificar** | `pending` | UUID design is explicit; until there's a concrete improvement without breaking current properties, it won't change. | [T1-N2-00](./REPORT-1.md#L630) |

---

## Resumen de cobertura

| Estado | Count | Hallazgos |
|--------|-------|-----------|
| `pending` | 10 | T1-N1-04, T1-N1-05, T1-N1-11, T1-N1-12, T1-N2-00, T1-N2-03, T1-N2-06, T1-N2-08, T1-N2-09, T2-N2-01 |
| `planning` | 0 | — |
| `in-progress` | 0 | — |
| `done` | 20 | T1-N1-03, T1-N1-06, T1-N1-07, T1-N1-08, T1-N1-09, T1-N1-10, T1-N1-13, T1-N1-14, T1-N1-16, T1-N2-01, T1-N2-02, T1-N2-04, T1-N2-05, T1-N2-07, T1-N2-11, T1-N2-13, T2-N1-01, T2-N1-02, T2-N2-02, T2-N2-03 |
| `wont-fix` | 5 | T1-N1-01, T1-N1-02, T1-N1-15, T1-N2-10, T1-N2-12 |
| `deferred` | 0 | — |
| **Total** | **35** | T1-N1-01..T1-N1-16, T1-N2-00..T1-N2-13, T2-N1-01..T2-N1-02, T2-N2-01..T2-N2-03 |
