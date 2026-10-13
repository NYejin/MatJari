---
name: ops-rules
description: 작업 방식 상세 규칙(모델 라우팅, 병렬 실행, 비용 규칙, worktree 격리 주의, 모델 확인 방법). 서브에이전트를 띄우거나 병렬 작업·워크플로우를 계획할 때, 또는 에이전트 model 지정이 의도대로 적용되는지 확인할 때 반드시 참고한다.
---

# 작업 방식 상세 (ops-rules)

CLAUDE.md '작업 방식'의 상세판. 기존 규칙(신규 파일 5개 이내, 확인 후 진행 등)은 그대로 유지한다.

## 모델
- 메인은 sonnet 5.5. 서브에이전트의 model은 `.claude/agents/*.md` frontmatter를 따른다. **Agent 호출 때 model 파라미터를 임의로 지정하지 않는다**(워크플로우 스크립트가 단계별로 지정하는 것은 허용).
- 라우팅:

| 에이전트 | model | 비고 |
|---|---|---|
| Explore, researcher, doc-writer | haiku | researcher는 3단계에서 생성. 최신 Haiku는 4.5(haiku 5.5 없음) |
| backend-dev, frontend-dev, wiki-curator | sonnet | 개발자는 `isolation: worktree`. wiki-curator는 3단계에서 생성 |
| db-schema-architect, code-reviewer | opus | 설계·리뷰 |
| 보안·동시성 최종 검증 | fable | 호출 때 지정 |
| seatmap-vision-engineer | 미지정 | 표에 없음. 좌석표 트랙 동결 |

- 중요한 설계·결정이 아니면 opus·fable을 쓰지 않는다.

## 병렬 규칙
- 읽기 전용 작업(탐색·리서치·리뷰·테스트)은 자유롭게 병렬.
- 쓰기 병렬은 서로 다른 파일 집합 + worktree 격리 + 에이전트당 신규 파일 5개 이내.
- Flyway 번호는 메인이 계획 단계에서 미리 배정하고, 마이그레이션은 한 에이전트만 쓴다.
- CLAUDE.md·HISTORY.md·STATS.md는 통합 단계에서 메인(또는 doc-writer)만 수정한다.
- 결과는 합쳐서 한 번에 보고하고 사용자 확인 후 병합한다. 설계·결정 항목은 병렬 중에도 반드시 사용자 확인을 받는다.

## 비용 규칙
- ultracode는 켜지 않는다.
- 큰 워크플로우는 작은 범위로 먼저 시험한다. 25개 넘는 에이전트나 큰 실행은 사용자 확인 후.
- 모든 서브에이전트가 CLAUDE.md를 읽으므로 CLAUDE.md는 짧게 유지한다(상세는 스킬로).

## 별칭을 쓰는 이유
frontmatter에는 `sonnet`/`haiku`/`opus`/`fable` 별칭을 쓴다. 전체 모델 ID는 버전이 바뀔 때마다 파일을 고쳐야 하지만, 별칭은 Claude Code가 최신 해당 모델로 해석한다. 대신 의도한 모델로 해석되는지 아래 방법으로 확인한다.

## 실제 모델이 맞는지 확인하는 방법
1. 해당 서브에이전트를 하나 실행한다(예: Explore에 간단한 탐색 요청).
2. 실행 중 또는 직후 `/tasks`에서 그 에이전트의 모델 표시를 확인한다.
3. 별칭이 해석되지 않거나 다른 모델로 나오면 frontmatter의 `model:`을 전체 모델 ID로 교체한다.
4. 환경변수 `CLAUDE_CODE_SUBAGENT_MODEL` / `CLAUDE_CODE_SUBAGENT_MODEL_FORCE`가 설정돼 있으면 에이전트별 라우팅이 무시될 수 있다. 현재는 미설정(2026-10-10 확인). 라우팅이 이상하면 먼저 이 변수를 확인한다.

## worktree 격리 주의 (backend-dev, frontend-dev)
- `isolation: worktree` 에이전트는 별도 체크아웃에서 작업한다. 변경은 메인 작업 트리에 즉시 보이지 않으며, 해당 브랜치/worktree를 병합·확인해야 반영된다.
- worktree에는 `node_modules`가 없다. 프론트 작업 전 `MatJari/frontend`에서 `npm install`(또는 `npm ci`)이 필요하다. 백엔드도 첫 빌드는 의존성 다운로드가 있을 수 있다.

## 워크플로우 (`.claude/workflows/*.js`)

공식 문서의 dynamic workflows 기능이다(Claude Code v2.1.154 이상). 파일은 마크다운이 아니라 JavaScript이고, 첫 문장이 `export const meta = { name, description }`(리터럴만)이며 본문은 top-level `await`를 쓴다. 쓸 수 있는 전역은 `agent()`, `pipeline()`, `parallel()`, `phase()`, `log()`, `args`이고 `import()`, `Date.now()`, `Math.random()`, 인자 없는 `new Date()`는 쓸 수 없다(시각·난수는 `args`로 받는다). 실행 중 사용자 입력은 받을 수 없으므로 승인이 필요한 지점에서 워크플로우를 나눈다.

| 워크플로우 | 하는 일 | 쓰기 | 에이전트 수(대략) |
|---|---|---|---|
| `plan-feature` | 읽기 전용 병렬 탐색 → 설계안 1건(opus) → 보고서에서 끝 | 없음 | 6 |
| `build-feature` | 승인된 계획을 파일 집합별 구현 → 테스트 → 통합 요약 | 있음 | 파일 집합 수 + 2 |
| `verify-feature` | 리뷰어 3명(opus) → 적대적 교차 검증(fable) → 높음·중간 지적 | 없음 | 4 |
| `sync-docs` | HISTORY·작업일지·위키 log 갱신(haiku) + STATS 재생성 | 문서만 | 4 |

- **검증 전 초안이다.** 단계별 모델 지정 옵션 이름(`model`)과 에이전트 worktree 격리 옵션은 공식 문서에서 확인하지 못했다. 사용자가 `/workflow-authoring` 스킬로 문법을 확인하고 `/workflows`에서 작은 범위로 시험 실행한 결과로 고친다. 격리가 확인되기 전에는 `build-feature`가 쓰기를 순차 실행한다(`parallelWrites`는 확인 뒤에만 켠다).
- **MySQL 통합 테스트는 실행마다 고유한 DB 이름·컨테이너·포트를 써야 한다.** 테스트는 시작할 때 `MATJARI_IT_JDBC_URL`의 DB를 Flyway clean하고 안전장치는 이름이 `_it`로 끝나는지만 검사하므로, 병렬 worktree가 같은 DB를 가리키면 서로 스키마를 지운다. `build-feature`는 `args.runId`로 `matjari_<runId>_it` 이름과 `matjari-tmp-<runId>` 컨테이너를 만든다.
- 규모 지침: `workflowSizeGuideline`은 조언일 뿐 상한이 아니다. 25개 넘는 에이전트나 큰 실행은 먼저 사용자 확인을 받는다. `ultracode`는 켜지 않는다.
