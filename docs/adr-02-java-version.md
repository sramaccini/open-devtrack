# ADR-02: Java version

Status: Proposed (31 October 2026) — decision D2 of the project plan

## Context

The back end is a Spring Boot 4.1 monolith that embeds the authz-library libraries. The options were Java 21 (the
reference code of Quadrava) and Java 25 LTS.

authz-library 2.0.1 is built with `java.version` 25 on Spring Boot 4.1.2: its jars are class files of Java 25 and do not
load on an older JVM. So Java 21 is not really available to OpenDevTrack, unless authz-library changes its target.

## Decision (proposed)

**Java 25 LTS** for the back end, its tests and its image; the Maven compiler `release` set to 25.

## Alternatives considered

- **Java 21.** Would need authz-library built for 21 as well, giving up nothing it needs from 25 today, but with a
  second support line to keep. No reason found to prefer it.

## Consequences

- CI and the image use a Java 25 runtime (Temurin 25). Developer machines need JDK 25 (on the current machine it is
  installed but not in the `PATH`: `JAVA_HOME` set by hand, see HANDOFF).
- The reference services of Quadrava stay on 21: they are separate processes and are not part of OpenDevTrack.
