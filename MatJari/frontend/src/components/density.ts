import { createContext, useContext } from "react";
import * as comfortable from "./ui";
import * as dense from "./adminUi";

// 화면 밀도: 관리자 화면(AdminLayout)은 true 를 내려 주고, 이용자 화면과 같이 쓰는 컴포넌트(입력칸·구역/회차 편집기 등)는
// useTokens() 로 그 밀도의 토큰을 골라 관리자 화면 안에서 글자 크기가 섞이지 않게 한다(2026-10-22 사용자 지시).
export const DenseContext = createContext(false);

export function useDense(): boolean {
  return useContext(DenseContext);
}

export function useTokens() {
  return useDense()
    ? { ui: dense.ui, input: dense.input, button: dense.button, linkButton: dense.linkButton }
    : { ui: comfortable.ui, input: comfortable.input, button: comfortable.button, linkButton: comfortable.linkButton };
}
