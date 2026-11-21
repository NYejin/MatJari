package com.matjari.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 클라이언트 IP 를 구하는 유일한 자리(속도 제한 키). 배포 구조는 (cloudflared 터널 또는 직접) -> nginx(web 컨테이너) -> backend 라서
 * getRemoteAddr() 는 항상 nginx 주소다. 그래서 직접 연결한 쪽이 신뢰 프록시({@code matjari.security.trusted-proxies}, CIDR 목록)일 때만
 * 프록시가 넣은 헤더를 읽는다. 신뢰 프록시가 아니면 헤더는 위조일 수 있어 getRemoteAddr() 를 그대로 쓴다.
 * <ul>
 *   <li>1순위 X-Real-IP: nginx 가 {@code proxy_set_header X-Real-IP ...} 로 덮어써 넘긴다(Cloudflare 경유면 nginx 가 CF-Connecting-IP 값을 X-Real-IP 로 넘긴다는 전제).</li>
 *   <li>2순위 X-Forwarded-For: 오른쪽부터 신뢰 프록시를 건너뛴 첫 주소(가장 오른쪽 신뢰되지 않은 주소). 왼쪽 값은 클라이언트가 위조할 수 있다.</li>
 * </ul>
 * Spring 의 server.forward-headers-strategy 는 모든 헤더를 신뢰하므로 쓰지 않는다. 헤더 값이 IP 리터럴이 아니면 무시한다(DNS 조회 없음).
 */
@Component
public class ClientIpResolver {

    static final String DEFAULT_TRUSTED =
            "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

    private record Cidr(byte[] network, int prefix) {
        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (addr[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Cidr> trusted;

    public ClientIpResolver(
            @Value("${matjari.security.trusted-proxies:" + DEFAULT_TRUSTED + "}") String[] trustedProxies) {
        List<Cidr> list = new ArrayList<>();
        for (String raw : trustedProxies) {
            String s = raw == null ? "" : raw.trim();
            if (!s.isEmpty()) {
                list.add(parseCidr(s));
            }
        }
        this.trusted = List.copyOf(list);
    }

    /** 속도 제한에 쓸 클라이언트 IP 문자열. */
    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        InetAddress remoteAddr = parseLiteral(remote);
        if (remoteAddr == null || !isTrusted(remoteAddr)) {
            return remote == null ? "" : remote;
        }
        String real = request.getHeader("X-Real-IP");
        InetAddress realAddr = real == null ? null : parseLiteral(real.trim());
        if (realAddr != null) {
            return realAddr.getHostAddress();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                InetAddress a = parseLiteral(parts[i].trim());
                if (a == null) {
                    break; // 알 수 없는 값을 만나면 그 왼쪽은 믿지 않는다
                }
                if (!isTrusted(a)) {
                    return a.getHostAddress();
                }
            }
        }
        return remoteAddr.getHostAddress();
    }

    private boolean isTrusted(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        for (Cidr c : trusted) {
            if (c.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** IP 리터럴만 받는다(호스트 이름이면 null — DNS 를 조회하지 않는다). */
    static InetAddress parseLiteral(String s) {
        if (s == null || s.isEmpty() || s.length() > 45) {
            return null;
        }
        boolean v6 = s.indexOf(':') >= 0;
        if (!(v6 ? IPV6.matcher(s).matches() : IPV4.matcher(s).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static Cidr parseCidr(String s) {
        int slash = s.indexOf('/');
        String ip = slash < 0 ? s : s.substring(0, slash);
        InetAddress a = parseLiteral(ip);
        if (a == null) {
            throw new IllegalStateException("matjari.security.trusted-proxies 값이 올바른 IP/CIDR 이 아닙니다: " + s);
        }
        int bits = a.getAddress().length * 8;
        int prefix = bits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(s.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("matjari.security.trusted-proxies 값이 올바른 IP/CIDR 이 아닙니다: " + s);
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalStateException("matjari.security.trusted-proxies 접두 길이가 범위를 벗어났습니다: " + s);
            }
        }
        return new Cidr(a.getAddress(), prefix);
    }
}
