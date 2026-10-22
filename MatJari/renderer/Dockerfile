# 공연 상세 화면을 그리는 헤드리스 Chromium(새 headless 모드). 백엔드만 닿는 내부 네트워크에서 DevTools(9222)를 연다.
# Chromium 은 DevTools 를 127.0.0.1:9223 에만 열고 socat 이 9222 로 넘긴다(새 headless 는 --remote-debugging-address 를 쓰지 않는다).
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends chromium socat fonts-noto-cjk ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -m -u 10001 renderer
USER renderer
ENV LANG=ko_KR.UTF-8 TZ=Asia/Seoul
EXPOSE 9222
CMD ["sh", "-c", "socat TCP-LISTEN:9222,fork,reuseaddr TCP:127.0.0.1:9223 & exec chromium --headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage --lang=ko-KR --remote-debugging-port=9223 --user-data-dir=/tmp/chrome about:blank"]
