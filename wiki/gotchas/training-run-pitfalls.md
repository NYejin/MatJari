---
title: 모델 학습 실행 함정 (백그라운드 셸, 출력 폴더, Python -I 인코딩)
type: gotcha
tags: [학습, 셸, Python, 인코딩, 평가]
sources: [../research/zone-shape-training-v2.md, ../../eval-data/report/train_v2_record.md]
updated: 2026-11-06
confidence: medium
status: draft
---

# 모델 학습 실행 함정

[구역 모양 학습 v2](../research/zone-shape-training-v2.md)를 하며 겪은 것이다.

## 1. 꺼진 것처럼 보이는 백그라운드 셸이 살아 있을 수 있다

- 재현: 메모리 정리로 셸이 꺼진 줄 알고 미세조정 대기열을 하나 더 걸었는데, 원래 대기열도 살아 있어 0~3번 겹을 두 프로세스가 동시에 학습했다. 끄지 못한 다른 반복 작업(NOL 좌석표 찾기)도 살아 있었다.
- 피해: GPU 경합으로 한 epoch가 약 22분으로 늘었고 한 겹은 CUDA 시간 초과로 죽었다.
- 해결: **대기열을 다시 걸기 전에 프로세스 목록(CommandLine 포함)으로 확인**한다. 이름만 보면 구분이 안 된다.

## 2. 두 학습이 같은 출력 폴더에 동시에 쓰면 체크포인트가 섞인다

- 같은 `runs/ft_v2_f0~3`에 두 프로세스가 썼고 체크포인트·로그가 섞였다.
- 해결: 섞인 폴더는 기록용으로 두고 평가에 쓰지 않았으며, 한 번에 하나씩 새 폴더(`ft_v2c_f0~3`)에 다시 학습했다. 실행마다 출력 폴더를 분리하는 것이 안전하다.

## 3. Python -I 는 PYTHONIOENCODING 을 무시한다

- 신뢰할 수 없는 입력을 다루려고 `python -I`(격리 모드)를 쓰면 환경변수 `PYTHONIOENCODING`이 적용되지 않아 Windows에서 한글 출력이 깨지거나 인코딩 오류가 난다.
- 해결: 스크립트 안에서 `sys.stdout.reconfigure(encoding='utf-8')`를 호출한다.
