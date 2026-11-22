---
title: 학습 도형 빈자리 채우기 (효과 작음, 운영 반영 미정)
type: decision
tags: [AI, 구역, RF-DETR, 평가, 미정]
sources: [../research/zone-shape-training-v2.md, ../../eval-data/report/train_v2_record.md, https://claude.ai/artifact/MeQpn8728fcxt9XQ4VGbwj, ../../CLAUDE.md]
updated: 2026-11-06
confidence: medium
status: draft
---

# 학습 도형 빈자리 채우기

새 결정이 아니라 평가 결과의 정리다. **운영 반영 여부는 미정**이며 확정 규칙은 [CLAUDE.md](../../CLAUDE.md) 'AI 회차·구역 읽기' 절(학습 모델 도형을 섞지 않는다는 현행 서술 포함)이 기준이다.

## 안

현재 파이프라인(B)이 놓친 자리에 학습 모델(RF-DETR Seg Nano)이 찾은 도형을 채우는 방식(C).

## 결과

- 재현율 93.6 -> 95.2%, 정밀도 97.0 -> 97.3% (38장·741구역, 새 AI 응답 3표본 평균). 세부는 [학습 v2 결과](../research/zone-shape-training-v2.md).
- **효과가 작다**: 증가분 대부분이 지도 1장(213216, +10구역)이고 그 장을 빼면 +0.3%p다. 모양 틀림은 21.3 -> 23.0으로 약간 늘었다.
- 표본 간 폭은 3.6%p -> 0.3%p로 줄어 안정성에는 도움이 된다.

## 반영 시 따져 볼 것

- 지도당 도형 추론 약 1.3초(GTX 960) 추가와 운영 서버의 모델 파일·실행 환경 부담.
- 라벨이 자동(검수 0%)이라 모델이 파이프라인의 약점을 그대로 배울 수 있다.
- 평가셋이 38장·멜론뿐이라 일반화는 미확인.
