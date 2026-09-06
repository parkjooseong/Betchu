package com.betchu.backend.starter;

public class InvalidStarterSelectionException extends RuntimeException {

  public InvalidStarterSelectionException() {
    super("스타팅 배츄 종류와 이름을 확인해 주세요. 이름은 앞뒤 공백을 제외한 1~10자이며 줄바꿈과 제어 문자를 사용할 수 없습니다.");
  }
}
